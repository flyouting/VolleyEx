/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.volley.misc;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个使用空间大小有边界的文件cache，每一个entry包含一个key和values。values是byte序列，按文件或者流来访问的。
 * 每一个value的长度在0---Integer.MAX_VALUE之间。 A cache that uses a bounded amount of
 * space on a filesystem. Each cache entry has a string key and a fixed number
 * of values. Each key must match the regex <strong>[a-z0-9_-]{1,64}</strong>.
 * Values are byte sequences, accessible as streams or files. Each value must be
 * between {@code 0} and {@code Integer.MAX_VALUE} bytes in length.
 * <p>
 * cache使用目录文件存储数据。文件路径必须是唯一的，可以删除和重写目录文件。多个进程同时使用同样的文件目录是不正确的 The cache stores
 * its data in a directory on the filesystem. This directory must be exclusive
 * to the cache; the cache may delete or overwrite files from its directory. It
 * is an error for multiple processes to use the same cache directory at the
 * same time.
 * <p>
 * cache限制了大小，当超出空间大小时，cache就会后台删除entry直到空间没有达到上限为止。空间大小限制不是严格的，
 * cache可能会暂时超过limit在等待文件删除的过程中。cache的limit不包括文件系统的头部和日志，
 * 所以空间大小敏感的应用应当设置一个保守的limit大小 This cache limits the number of bytes that it
 * will store on the filesystem. When the number of stored bytes exceeds the
 * limit, the cache will remove entries in the background until the limit is
 * satisfied. The limit is not strict: the cache may temporarily exceed it while
 * waiting for files to be deleted. The limit does not include filesystem
 * overhead or the cache journal so space-sensitive applications should set a
 * conservative limit.
 * <p>
 * Clients call {@link #edit} to create or update the values of an entry. An
 * entry may have only one editor at one time; if a value is not available to be
 * edited then {@link #edit} will return null.
 * <ul>
 * <li>When an entry is being <strong>created</strong> it is necessary to supply
 * a full set of values; the empty value should be used as a placeholder if
 * necessary.
 * <li>When an entry is being <strong>edited</strong>, it is not necessary to
 * supply data for every value; values default to their previous value.
 * </ul>
 * Every {@link #edit} call must be matched by a call to {@link Editor#commit}
 * or {@link Editor#abort}. Committing is atomic: a read observes the full set
 * of values as they were before or after the commit, but never a mix of values.
 * 调用edit（）来创建或者更新entry的值，一个entry同时只能有一个editor；如果值不可被编辑就返回null。
 * 当entry被创建时必须提供一个value。空的value应当用占位符表示。当entry被编辑的时候，必须提供value。 每次调用必须有匹配Editor
 * commit或abort，commit是原子操作，读必须在commit前或者后，不会造成值混乱。
 * <p>
 * Clients call {@link #get} to read a snapshot of an entry. The read will
 * observe the value at the time that {@link #get} was called. Updates and
 * removals after the call do not impact ongoing reads.
 * 调用get来读entry的快照。当get调用时读者读其值，更新或者删除不会影响先前的读
 * <p>
 * 该类可以容忍一些I/O errors。如果文件丢失啦，相应的entry就会被drop。写cache时如果error发生，edit将失败。
 * 调用者应当相应的处理其它问题 This class is tolerant of some I/O errors. If files are
 * missing from the filesystem, the corresponding entries will be dropped from
 * the cache. If an error occurs while writing a cache value, the edit will fail
 * silently. Callers should handle other problems by catching
 * {@code IOException} and responding appropriately.
 */
public final class DiskLruCache implements Closeable {
    static final String JOURNAL_FILE = "journal";
    static final String JOURNAL_FILE_TEMP = "journal.tmp";
    static final String JOURNAL_FILE_BACKUP = "journal.bkp";
    static final String MAGIC = "libcore.io.DiskLruCache";
    static final String VERSION_1 = "1";
    static final long ANY_SEQUENCE_NUMBER = -1;
    static final Pattern LEGAL_KEY_PATTERN = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final String CLEAN = "CLEAN";
    private static final String DIRTY = "DIRTY";
    private static final String REMOVE = "REMOVE";
    private static final String READ = "READ";

    /*
     * This cache uses a journal file named "journal". A typical journal file
     * looks like this: libcore.io.DiskLruCache 1 100 2 
     * CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832 21054 
     * DIRTY 335c4c6028171cfddfbaae1a9c313c52 
     * CLEAN 335c4c6028171cfddfbaae1a9c313c52 3934 2342 
     * REMOVE 335c4c6028171cfddfbaae1a9c313c52 
     * DIRTY 1ab96a171faeeee38496d8b330771a7a 
     * CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234 
     * READ 335c4c6028171cfddfbaae1a9c313c52 
     * READ 3400330d1dfc7f3f7f4b8d4d803dfcf6
     * 前5行分别为一个常量字符串："libcore.io.DiskLruCache"，该DiskLruCache的版本，应用程序的版本，
     * 每个条目中保存值的个数，以及一个空行。 The first five lines of the journal form its header.
     * They are the constant string "libcore.io.DiskLruCache", the disk cache's
     * version, the application's version, the value count, and a blank line.
     * 该文件中，随后记录的都是一个entry的状态。每行包括下面几项内容：一个状态，一个key，和可选择的特定状态的值。
     * DIRTY：追踪那些活跃的条目，
     * 它们目前正在被创建或更新。每一个成功的DIRTY行后应该跟随一个CLEAN或REMOVE行。DIRTY行如果没有一个CLEAN或REMOVE行与它匹配
     * ，表明那是一个临时文件应该被删除。 CLEAN：跟踪一个发布成功的entry，并可以读取。一个发布行后跟着其文件的长度。
     * READ：跟踪对LRU的访问。 REMOVE：跟踪被删除的entry。 Each of the subsequent lines in the
     * file is a record of the state of a cache entry. Each line contains
     * space-separated values: a state, a key, and optional state-specific
     * values. o DIRTY lines track that an entry is actively being created or
     * updated. Every successful DIRTY action should be followed by a CLEAN or
     * REMOVE action. DIRTY lines without a matching CLEAN or REMOVE indicate
     * that temporary files may need to be deleted. o CLEAN lines track a cache
     * entry that has been successfully published and may be read. A publish
     * line is followed by the lengths of each of its values. o READ lines track
     * accesses for LRU. o REMOVE lines track entries that have been deleted.
     * The journal file is appended to as cache operations occur. The journal
     * may occasionally be compacted by dropping redundant lines. A temporary
     * file named "journal.tmp" will be used during compaction; that file should
     * be deleted if it exists when the cache is opened.
     */

    private final File directory;// 指向该DiskLruCache的工作目录
    private final File journalFile;// 指向journal文件
    private final File journalFileTmp;// 当构建一个journal文件时，先会生成一个journalTmp文件，当文件构建完成时，
                                      // 会将该journalTmp重命名为journal，这是一个临时文件
    private final File journalFileBackup;// journal备份文件
    private final int appVersion;// 应用版本号
    private long maxSize;// 是该DiskLruCache所允许的最大缓存空间
    private final int valueCount;// 每个entry对应的缓存文件的格式，一般情况下，该值为1.
    private long size = 0;// DiskLruCache当前缓存的大小
    private Writer journalWriter;// 指向journal文件，主要向该文件中写内容
    private final LinkedHashMap<String, Entry> lruEntries = new LinkedHashMap<String, Entry>(0,
            0.75f, true);// 当前entry的列表
    private int redundantOpCount;// 当前journal文件中entry状态记录的个数，主要用来当该值大于一定限制时，对journal文件进行清理

    /**
     * To differentiate between old and current snapshots, each entry is given a
     * sequence number each time an edit is committed. A snapshot is stale if
     * its sequence number is not equal to its entry's sequence number.
     * 新旧entry的特征值
     * 为了区分旧的和当前的快照，每个entry在每次修改提交的时候都会分一个特征值，当快照的特征值跟entry的特征值不一样，那快照就是过期的
     */
    private long nextSequenceNumber = 0;

    /**
     * This cache uses a single background thread to evict entries. 后台单线程回收entry
     */
    final ThreadPoolExecutor executorService = new ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>());
    private final Callable<Void> cleanupCallable = new Callable<Void>() {
        public Void call() throws Exception {
            synchronized (DiskLruCache.this) {
                if (journalWriter == null) {
                    return null; // Closed.
                }
                trimToSize();
                if (journalRebuildRequired()) {
                    rebuildJournal();
                    redundantOpCount = 0;
                }
            }
            return null;
        }
    };

    private DiskLruCache(File directory, int appVersion, int valueCount, long maxSize) {
        this.directory = directory;
        this.appVersion = appVersion;
        this.journalFile = new File(directory, JOURNAL_FILE);
        this.journalFileTmp = new File(directory, JOURNAL_FILE_TEMP);
        this.journalFileBackup = new File(directory, JOURNAL_FILE_BACKUP);
        this.valueCount = valueCount;
        this.maxSize = maxSize;
    }

    /**
     * Opens the cache in {@code directory}, creating a cache if none exists
     * there.
     * 
     * @param directory a writable directory
     * @param valueCount the number of values per cache entry. Must be positive.
     * @param maxSize the maximum number of bytes this cache should use to store
     * @throws IOException if reading or writing the cache directory fails
     */
    public static DiskLruCache open(File directory, int appVersion, int valueCount, long maxSize)
            throws IOException {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }
        if (valueCount <= 0) {
            throw new IllegalArgumentException("valueCount <= 0");
        }

        // 如果存在一个备份文件，则使用这个备份文件
        File backupFile = new File(directory, JOURNAL_FILE_BACKUP);
        if (backupFile.exists()) {
            File journalFile = new File(directory, JOURNAL_FILE);
            // 如果journal 文件也存在，就删除备份文件
            if (journalFile.exists()) {
                backupFile.delete();
            } else {
                renameTo(backupFile, journalFile, false);
            }
        }

        // Prefer to pick up where we left off.
        DiskLruCache cache = new DiskLruCache(directory, appVersion, valueCount, maxSize);
        if (cache.journalFile.exists()) {
            try {
                cache.readJournal();
                cache.processJournal();
                cache.journalWriter = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(cache.journalFile, true), Utils.US_ASCII));
                return cache;
            } catch (IOException journalIsCorrupt) {
                System.out.println("DiskLruCache " + directory + " is corrupt: "
                        + journalIsCorrupt.getMessage() + ", removing");
                cache.delete();
            }
        }

        // Create a new empty cache.
        directory.mkdirs();
        cache = new DiskLruCache(directory, appVersion, valueCount, maxSize);
        cache.rebuildJournal();
        return cache;
    }

    /**
     * 读取日志信息，前五行为固定内容，其他每行都是一条数据信息
     * 
     * @throws IOException
     */
    private void readJournal() throws IOException {
        StrictLineReader reader = new StrictLineReader(new FileInputStream(journalFile),
                Utils.US_ASCII);
        try {
            String magic = reader.readLine();
            String version = reader.readLine();
            String appVersionString = reader.readLine();
            String valueCountString = reader.readLine();
            String blank = reader.readLine();
            if (!MAGIC.equals(magic) || !VERSION_1.equals(version)
                    || !Integer.toString(appVersion).equals(appVersionString)
                    || !Integer.toString(valueCount).equals(valueCountString) || !"".equals(blank)) {
                throw new IOException("unexpected journal header: [" + magic + ", " + version
                        + ", " + valueCountString + ", " + blank + "]");
            }

            int lineCount = 0;
            while (true) {
                try {
                    readJournalLine(reader.readLine());
                    lineCount++;
                } catch (EOFException endOfJournal) {
                    break;
                }
            }
            redundantOpCount = lineCount - lruEntries.size();
        } finally {
            Utils.closeQuietly(reader);
        }
    }

    /**
     * 逐行读取一条数据信息
     * 
     * @param line
     * @throws IOException
     */
    private void readJournalLine(String line) throws IOException {
        int firstSpace = line.indexOf(' ');
        if (firstSpace == -1) {
            throw new IOException("unexpected journal line: " + line);
        }

        int keyBegin = firstSpace + 1;
        int secondSpace = line.indexOf(' ', keyBegin);
        final String key;
        if (secondSpace == -1) {
            key = line.substring(keyBegin);
            if (firstSpace == REMOVE.length() && line.startsWith(REMOVE)) {
                lruEntries.remove(key);
                return;
            }
        } else {
            key = line.substring(keyBegin, secondSpace);
        }

        Entry entry = lruEntries.get(key);
        if (entry == null) {
            entry = new Entry(key);
            lruEntries.put(key, entry);
        }

        if (secondSpace != -1 && firstSpace == CLEAN.length() && line.startsWith(CLEAN)) {
            String[] parts = line.substring(secondSpace + 1).split(" ");
            entry.readable = true;
            entry.currentEditor = null;
            entry.setLengths(parts);
        } else if (secondSpace == -1 && firstSpace == DIRTY.length() && line.startsWith(DIRTY)) {
            entry.currentEditor = new Editor(entry);
        } else if (secondSpace == -1 && firstSpace == READ.length() && line.startsWith(READ)) {
            // This work was already done by calling lruEntries.get().
        } else {
            throw new IOException("unexpected journal line: " + line);
        }
    }

    /**
     * Computes the initial size and collects garbage as a part of opening the
     * cache. Dirty entries are assumed to be inconsistent and will be deleted.
     * 计算初始化cache的初始化大小和收集垃圾。Dirty entry假定不一致将会被删掉
     */
    private void processJournal() throws IOException {
        deleteIfExists(journalFileTmp);// 删除日志文件
        for (Iterator<Entry> i = lruEntries.values().iterator(); i.hasNext();) {
            Entry entry = i.next();
            if (entry.currentEditor == null) {
                for (int t = 0; t < valueCount; t++) {
                    size += entry.lengths[t];
                }
            } else {
                entry.currentEditor = null;
                for (int t = 0; t < valueCount; t++) {
                    deleteIfExists(entry.getCleanFile(t));
                    deleteIfExists(entry.getDirtyFile(t));
                }
                i.remove();
            }
        }
    }

    /**
     * 创建一个新的删掉冗余信息的日志。替换当前的日志 Creates a new journal that omits redundant
     * information. This replaces the current journal if it exists.
     */
    private synchronized void rebuildJournal() throws IOException {
        if (journalWriter != null) {
            journalWriter.close();
        }

        Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(
                journalFileTmp), Utils.US_ASCII));
        try {
            writer.write(MAGIC);
            writer.write("\n");
            writer.write(VERSION_1);
            writer.write("\n");
            writer.write(Integer.toString(appVersion));
            writer.write("\n");
            writer.write(Integer.toString(valueCount));
            writer.write("\n");
            writer.write("\n");

            for (Entry entry : lruEntries.values()) {
                if (entry.currentEditor != null) {
                    writer.write(DIRTY + ' ' + entry.key + '\n');
                } else {
                    writer.write(CLEAN + ' ' + entry.key + entry.getLengths() + '\n');
                }
            }
        } finally {
            writer.close();
        }

        if (journalFile.exists()) {
            renameTo(journalFile, journalFileBackup, true);
        }
        renameTo(journalFileTmp, journalFile, false);
        journalFileBackup.delete();

        journalWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(journalFile,
                true), Utils.US_ASCII));
    }

    private static void deleteIfExists(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException();
        }
    }

    private static void renameTo(File from, File to, boolean deleteDestination) throws IOException {
        if (deleteDestination) {
            deleteIfExists(to);
        }
        if (!from.renameTo(to)) {
            throw new IOException();
        }
    }

    /**
     * 返回key对应的entry的snapshot，当key相应的entry不存在或者当前不可读时返回null。
     * 如果返回相应的值，它就会被移动到LRU队列的头部。 Returns a snapshot of the entry named
     * {@code key}, or null if it doesn't exist is not currently readable. If a
     * value is returned, it is moved to the head of the LRU queue.
     */
    public synchronized Snapshot get(String key) throws IOException {
        checkNotClosed();// 检查cache是否已关闭
        validateKey(key);// 验证key格式的正确性
        Entry entry = lruEntries.get(key);
        if (entry == null) {
            return null;
        }

        if (!entry.readable) {
            return null;
        }

        // Open all streams eagerly to guarantee that we see a single published
        // snapshot. If we opened streams lazily then the streams could come
        // from different edits.
        InputStream[] ins = new InputStream[valueCount];
        try {
            for (int i = 0; i < valueCount; i++) {
                ins[i] = new FileInputStream(entry.getCleanFile(i));
            }
        } catch (FileNotFoundException e) {
            // A file must have been deleted manually!
            for (int i = 0; i < valueCount; i++) {
                if (ins[i] != null) {
                    Utils.closeQuietly(ins[i]);
                } else {
                    break;
                }
            }
            return null;
        }

        redundantOpCount++;
        journalWriter.append(READ + ' ' + key + '\n');
        if (journalRebuildRequired()) {
            executorService.submit(cleanupCallable);
        }

        return new Snapshot(key, entry.sequenceNumber, ins, entry.lengths);
    }

    /**
     * 用key和序列号生成一个editor Returns an editor for the entry named {@code key}, or
     * null if another edit is in progress.
     */
    public Editor edit(String key) throws IOException {
        return edit(key, ANY_SEQUENCE_NUMBER);
    }

    private synchronized Editor edit(String key, long expectedSequenceNumber) throws IOException {
        checkNotClosed();
        validateKey(key);
        Entry entry = lruEntries.get(key);
        if (expectedSequenceNumber != ANY_SEQUENCE_NUMBER
                && (entry == null || entry.sequenceNumber != expectedSequenceNumber)) {
            return null; // Snapshot is stale.
        }
        if (entry == null) {
            entry = new Entry(key);
            lruEntries.put(key, entry);
        } else if (entry.currentEditor != null) {
            return null; // Another edit is in progress.
        }

        Editor editor = new Editor(entry);
        entry.currentEditor = editor;

        // Flush the journal before creating files to prevent file leaks.
        journalWriter.write(DIRTY + ' ' + key + '\n');
        journalWriter.flush();
        return editor;
    }

    /** Returns the directory where this cache stores its data. */
    public File getDirectory() {
        return directory;
    }

    /**
     * Returns the maximum number of bytes that this cache should use to store
     * its data.
     */
    public synchronized long getMaxSize() {
        return maxSize;
    }

    /**
     * Changes the maximum number of bytes the cache can store and queues a job
     * to trim the existing store, if necessary.
     */
    public synchronized void setMaxSize(long maxSize) {
        this.maxSize = maxSize;
        executorService.submit(cleanupCallable);
    }

    /**
     * Returns the number of bytes currently being used to store the values in
     * this cache. This may be greater than the max size if a background
     * deletion is pending.
     */
    public synchronized long size() {
        return size;
    }

    private synchronized void completeEdit(Editor editor, boolean success) throws IOException {
        Entry entry = editor.entry;
        if (entry.currentEditor != editor) {
            throw new IllegalStateException();
        }

        // If this edit is creating the entry for the first time, every index
        // must have a value.
        if (success && !entry.readable) {
            for (int i = 0; i < valueCount; i++) {
                if (!editor.written[i]) {
                    editor.abort();
                    throw new IllegalStateException(
                            "Newly created entry didn't create value for index " + i);
                }
                if (!entry.getDirtyFile(i).exists()) {
                    editor.abort();
                    return;
                }
            }
        }

        for (int i = 0; i < valueCount; i++) {
            File dirty = entry.getDirtyFile(i);
            if (success) {
                if (dirty.exists()) {
                    File clean = entry.getCleanFile(i);
                    dirty.renameTo(clean);
                    long oldLength = entry.lengths[i];
                    long newLength = clean.length();
                    entry.lengths[i] = newLength;
                    size = size - oldLength + newLength;
                }
            } else {
                deleteIfExists(dirty);
            }
        }

        redundantOpCount++;
        entry.currentEditor = null;
        if (entry.readable | success) {
            entry.readable = true;
            journalWriter.write(CLEAN + ' ' + entry.key + entry.getLengths() + '\n');
            if (success) {
                entry.sequenceNumber = nextSequenceNumber++;
            }
        } else {
            lruEntries.remove(entry.key);
            journalWriter.write(REMOVE + ' ' + entry.key + '\n');
        }
        journalWriter.flush();

        if (size > maxSize || journalRebuildRequired()) {
            executorService.submit(cleanupCallable);
        }
    }

    /**
     * 当日志大小减半并且删掉至少2000项时重新构造日志 We only rebuild the journal when it will halve
     * the size of the journal and eliminate at least 2000 ops.
     */
    private boolean journalRebuildRequired() {
        final int redundantOpCompactThreshold = 2000;
        return redundantOpCount >= redundantOpCompactThreshold //
                && redundantOpCount >= lruEntries.size();
    }

    /**
     * 删除key相应的entry，被编辑的Entry不能被remove Drops the entry for {@code key} if it
     * exists and can be removed. Entries actively being edited cannot be
     * removed.
     * 
     * @return true if an entry was removed.
     */
    public synchronized boolean remove(String key) throws IOException {
        checkNotClosed();
        validateKey(key);
        Entry entry = lruEntries.get(key);
        if (entry == null || entry.currentEditor != null) {
            return false;
        }

        for (int i = 0; i < valueCount; i++) {
            File file = entry.getCleanFile(i);
            if (file.exists() && !file.delete()) {
                throw new IOException("failed to delete " + file);
            }
            size -= entry.lengths[i];
            entry.lengths[i] = 0;
        }

        redundantOpCount++;
        journalWriter.append(REMOVE + ' ' + key + '\n');
        lruEntries.remove(key);

        if (journalRebuildRequired()) {
            executorService.submit(cleanupCallable);
        }

        return true;
    }

    /**
     * Returns true if this cache has been closed. 判断cache是否已经关闭
     */
    public synchronized boolean isClosed() {
        return journalWriter == null;
    }

    // 检查cache是否已经关闭
    private void checkNotClosed() {
        if (journalWriter == null) {
            throw new IllegalStateException("cache is closed");
        }
    }

    /** Force buffered operations to the filesystem. */
    public synchronized void flush() throws IOException {
        checkNotClosed();
        trimToSize();
        journalWriter.flush();
    }

    /** Closes this cache. Stored values will remain on the filesystem. */
    public synchronized void close() throws IOException {
        if (journalWriter == null) {
            return; // Already closed.
        }
        for (Entry entry : new ArrayList<Entry>(lruEntries.values())) {
            if (entry.currentEditor != null) {
                entry.currentEditor.abort();
            }
        }
        trimToSize();
        journalWriter.close();
        journalWriter = null;
    }

    // 回收删除某些entry到空间大小满足maxsize
    private void trimToSize() throws IOException {
        while (size > maxSize) {
            Map.Entry<String, Entry> toEvict = lruEntries.entrySet().iterator().next();
            remove(toEvict.getKey());
        }
    }

    /**
     * 关闭删除cache，所有文件都会被删除 Closes the cache and deletes all of its stored
     * values. This will delete all files in the cache directory including files
     * that weren't created by the cache.
     */
    public void delete() throws IOException {
        close();
        Utils.deleteContents(directory);
    }

    private void validateKey(String key) {
        Matcher matcher = LEGAL_KEY_PATTERN.matcher(key);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("keys must match regex [a-z0-9_-]{1,64}: \"" + key
                    + "\"");
        }
    }

    private static String inputStreamToString(InputStream in) throws IOException {
        return Utils.readFully(new InputStreamReader(in, Utils.UTF_8));
    }

    /**
     * A snapshot of the values for an entry.
     * 该类表示DiskLruCache中每一个entry中缓存文件的快照，它持有该entry中每个文件的
     * inputStream，通过该inputStream可读取该文件的内容
     */
    public final class Snapshot implements Closeable {
        private final String key;
        private final long sequenceNumber;
        private final InputStream[] ins;
        private final long[] lengths;

        private Snapshot(String key, long sequenceNumber, InputStream[] ins, long[] lengths) {
            this.key = key;
            this.sequenceNumber = sequenceNumber;
            this.ins = ins;
            this.lengths = lengths;
        }

        /**
         * 返回entry快照的editor，如果entry已经更新了或者另一个edit正在处理过程中返回null Returns an editor
         * for this snapshot's entry, or null if either the entry has changed
         * since this snapshot was created or if another edit is in progress.
         */
        public Editor edit() throws IOException {
            return DiskLruCache.this.edit(key, sequenceNumber);
        }

        /** Returns the unbuffered stream with the value for {@code index}. */
        public InputStream getInputStream(int index) {
            return ins[index];
        }

        /** Returns the string value for {@code index}. */
        public String getString(int index) throws IOException {
            return inputStreamToString(getInputStream(index));
        }

        /** Returns the byte length of the value for {@code index}. */
        public long getLength(int index) {
            return lengths[index];
        }

        public void close() {
            for (InputStream in : ins) {
                Utils.closeQuietly(in);
            }
        }
    }

    private static final OutputStream NULL_OUTPUT_STREAM = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            // Eat all writes silently. Nom nom.
        }
    };

    /** Edits the values for an entry. 该类控制对每一个entry的读写操作 */
    public final class Editor {
        private final Entry entry;
        private final boolean[] written;
        private boolean hasErrors;
        private boolean committed;

        private Editor(Entry entry) {
            this.entry = entry;
            this.written = (entry.readable) ? null : new boolean[valueCount];
        }

        /**
         * 返回一个最后提交的entry的不缓存输入流，如果没有值被提交过返回null Returns an unbuffered input
         * stream to read the last committed value, or null if no value has been
         * committed.
         */
        public InputStream newInputStream(int index) throws IOException {
            synchronized (DiskLruCache.this) {
                if (entry.currentEditor != this) {
                    throw new IllegalStateException();
                }
                if (!entry.readable) {
                    return null;
                }
                try {
                    return new FileInputStream(entry.getCleanFile(index));
                } catch (FileNotFoundException e) {
                    return null;
                }
            }
        }

        /**
         * 返回最后提交的entry的文件内容，字符串形式 Returns the last committed value as a string,
         * or null if no value has been committed.
         */
        public String getString(int index) throws IOException {
            InputStream in = newInputStream(index);
            return in != null ? inputStreamToString(in) : null;
        }

        /**
         * 返回一个新的无缓冲的输出流，写文件时如果潜在的输出流存在错误，这个edit将被废弃 Returns a new unbuffered
         * output stream to write the value at {@code index}. If the underlying
         * output stream encounters errors when writing to the filesystem, this
         * edit will be aborted when {@link #commit} is called. The returned
         * output stream does not throw IOExceptions.
         */
        public OutputStream newOutputStream(int index) throws IOException {
            synchronized (DiskLruCache.this) {
                if (entry.currentEditor != this) {
                    throw new IllegalStateException();
                }
                if (!entry.readable) {
                    written[index] = true;
                }
                File dirtyFile = entry.getDirtyFile(index);
                FileOutputStream outputStream;
                try {
                    outputStream = new FileOutputStream(dirtyFile);
                } catch (FileNotFoundException e) {
                    // Attempt to recreate the cache directory.
                    directory.mkdirs();
                    try {
                        outputStream = new FileOutputStream(dirtyFile);
                    } catch (FileNotFoundException e2) {
                        // We are unable to recover. Silently eat the writes.
                        return NULL_OUTPUT_STREAM;
                    }
                }
                return new FaultHidingOutputStream(outputStream);
            }
        }

        /**
         * Sets the value at {@code index} to {@code value}.
         * 返回一个新的无缓冲的输出流，写文件时如果潜在的输出流存在错误，这个edit将被废弃。
         */
        public void set(int index, String value) throws IOException {
            Writer writer = null;
            try {
                writer = new OutputStreamWriter(newOutputStream(index), Utils.UTF_8);
                writer.write(value);
            } finally {
                Utils.closeQuietly(writer);
            }
        }

        /**
         * commit提交编辑的结果，释放edit锁然后其它edit可以启动 Commits this edit so it is visible
         * to readers. This releases the edit lock so another edit may be
         * started on the same key.
         */
        public void commit() throws IOException {
            if (hasErrors) {
                completeEdit(this, false);
                remove(entry.key); // The previous entry is stale.
            } else {
                completeEdit(this, true);
            }
            committed = true;
        }

        /**
         * 废弃edit，释放edit锁然后其它edit可以启动 Aborts this edit. This releases the edit
         * lock so another edit may be started on the same key.
         */
        public void abort() throws IOException {
            completeEdit(this, false);
        }

        public void abortUnlessCommitted() {
            if (!committed) {
                try {
                    abort();
                } catch (IOException ignored) {
                }
            }
        }

        private class FaultHidingOutputStream extends FilterOutputStream {
            private FaultHidingOutputStream(OutputStream out) {
                super(out);
            }

            @Override
            public void write(int oneByte) {
                try {
                    out.write(oneByte);
                } catch (IOException e) {
                    hasErrors = true;
                }
            }

            @Override
            public void write(byte[] buffer, int offset, int length) {
                try {
                    out.write(buffer, offset, length);
                } catch (IOException e) {
                    hasErrors = true;
                }
            }

            @Override
            public void close() {
                try {
                    out.close();
                } catch (IOException e) {
                    hasErrors = true;
                }
            }

            @Override
            public void flush() {
                try {
                    out.flush();
                } catch (IOException e) {
                    hasErrors = true;
                }
            }
        }
    }

    /**
     * 该类表示DiskLruCache中每一个条目
     */
    private final class Entry {
        private final String key;

        /**
         * Lengths of this entry's files. 该entry中每个文件的长度，该数组长度为valueCount
         */
        private final long[] lengths;

        /**
         * True if this entry has ever been published. 该entry曾经被发布过，该项为true
         */
        private boolean readable;

        /**
         * 该entry所对应的editor The ongoing edit or null if this entry is not being
         * edited.
         */
        private Editor currentEditor;

        /**
         * 最近编辑这个entry的序列号 The sequence number of the most recently committed
         * edit to this entry.
         */
        private long sequenceNumber;

        private Entry(String key) {
            this.key = key;
            this.lengths = new long[valueCount];
        }

        public String getLengths() throws IOException {
            StringBuilder result = new StringBuilder();
            for (long size : lengths) {
                result.append(' ').append(size);
            }
            return result.toString();
        }

        /** Set lengths using decimal numbers like "10123". */
        private void setLengths(String[] strings) throws IOException {
            if (strings.length != valueCount) {
                throw invalidLengths(strings);
            }

            try {
                for (int i = 0; i < strings.length; i++) {
                    lengths[i] = Long.parseLong(strings[i]);
                }
            } catch (NumberFormatException e) {
                throw invalidLengths(strings);
            }
        }

        private IOException invalidLengths(String[] strings) throws IOException {
            throw new IOException("unexpected journal line: " + java.util.Arrays.toString(strings));
        }

        public File getCleanFile(int i) {
            return new File(directory, key + "." + i);
        }

        public File getDirtyFile(int i) {
            return new File(directory, key + "." + i + ".tmp");
        }
    }
}

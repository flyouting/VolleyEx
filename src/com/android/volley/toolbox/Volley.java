/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.volley.toolbox;

import java.io.File;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.http.AndroidHttpClient;
import android.os.Build;

import com.android.volley.Network;
import com.android.volley.RequestQueue;
import com.android.volley.cache.Cache;
import com.android.volley.cache.DiskBasedCache;

public class Volley {

    /**
     * 默认的磁盘缓冲区文件夹名称。 Default on-disk cache directory.
     */
    private static final String DEFAULT_CACHE_DIR = "volley";

    /**
     * 创建一个默认的请求队列实例，启动worker线程池,线程池数量为默认值 4 Creates a default instance of the
     * worker pool and calls {@link RequestQueue#start()} on it.
     * 
     * @param context A {@link Context} 用于创建 cache 文件夹.
     * @param stack An {@link HttpStack} 用于网络, 默认为null.
     * @return
     */
    public static RequestQueue newRequestQueue(Context context, HttpStack stack) {
        return newRequestQueue(context, stack, null, -1);
    }

    /**
     * 创建一个默认的请求队列实例，启动worker线程池,线程池数量为默认值 4，L2级缓冲为默认的DiskBasedCache Creates a
     * default instance of the worker pool and calls
     * {@link RequestQueue#start()} on it.
     * 
     * @param context A {@link Context} 用于创建 cache 文件夹.
     * @param stack An {@link HttpStack} 用于网络, 默认为null.
     * @param cache L2级磁盘缓冲区
     * @return
     */
    public static RequestQueue newRequestQueue(Context context, HttpStack stack, Cache cache) {
        return newRequestQueue(context, stack, cache, -1);
    }

    /**
     * 创建一个默认的请求队列实例，启动worker线程池 Creates a default instance of the worker pool
     * and calls {@link RequestQueue#start()} on it.
     * 
     * @param context A {@link Context} 用于创建 cache 文件夹.
     * @param stack An {@link HttpStack} 用于网络, 默认为null.
     * @param threadPoolSize 线程池数量
     * @param cache L2级磁盘缓冲区
     * @return A started {@link RequestQueue} instance.
     */
    public static RequestQueue newRequestQueue(Context context, HttpStack stack, Cache cache,
            int threadPoolSize) {
        File cacheDir = new File(context.getCacheDir(), DEFAULT_CACHE_DIR);

        String userAgent = "volley/0";
        try {
            String packageName = context.getPackageName();
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            userAgent = packageName + "/" + info.versionCode;
        } catch (NameNotFoundException e) {
        }

        if (stack == null) {
            if (Build.VERSION.SDK_INT >= 9) {
                stack = new HurlStack();
            } else {
                // Gingerbread之前的版本, 采用HttpUrlConnection 不太合适.
                // 原因参考这里:
                // http://android-developers.blogspot.com/2011/09/androids-http-clients.html
                stack = new HttpClientStack(AndroidHttpClient.newInstance(userAgent));
            }
        }

        Network network = new BasicNetwork(stack);
        RequestQueue queue;
        if (threadPoolSize <= 0) {
            queue = new RequestQueue(cache == null ? new DiskBasedCache(cacheDir) : cache, network);
        } else {
            queue = new RequestQueue(cache == null ? new DiskBasedCache(cacheDir) : cache, network,
                    threadPoolSize);
        }
        queue.start();

        return queue;
    }

    /**
     * 创建一个默认的请求队列实例，启动worker线程池 Creates a default instance of the worker pool
     * and calls {@link RequestQueue#start()} on it.
     * 
     * @param context A {@link Context} 用于创建 cache 文件夹.
     * @return A started {@link RequestQueue} instance.
     */
    public static RequestQueue newRequestQueue(Context context) {
        return newRequestQueue(context, null);
    }
}

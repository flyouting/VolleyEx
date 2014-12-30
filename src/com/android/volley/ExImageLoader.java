/*
 * Copyright 2012 Google Inc.
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

package com.android.volley;

import java.util.ArrayList;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.Build;
import android.widget.ImageView;

import com.android.volley.cache.ImageCache;
import com.android.volley.error.VolleyError;
import com.android.volley.toolbox.Volley;

/**
 * A class that wraps up remote image loading requests using the Volley library
 * combined with a memory cache. An single instance of this class should be
 * created once when your Activity or Fragment is created, then use
 * {@link #get(String, android.widget.ImageView)} or one of the variations to
 * queue the image to be fetched and loaded from the network. Loading images in
 * a {@link android.widget.ListView} or {@link android.widget.GridView} is also
 * supported but you must store the {@link com.android.volley.Request} in your
 * ViewHolder type class and pass it into loadImage to ensure the request is
 * canceled as views are recycled.
 */
public class ExImageLoader extends com.android.volley.toolbox.ImageLoader {

    private static final int ANIMATION_FADE_IN_TIME = 250;
    private static final ColorDrawable transparentDrawable = new ColorDrawable(
            android.R.color.transparent);
    private static final int HALF_FADE_IN_TIME = ANIMATION_FADE_IN_TIME / 2;
    private static final String CACHE_DIR = "images";

    private Resources mResources;
    private ArrayList<Drawable> mPlaceHolderDrawables;
    private boolean mFadeInImage = true;
    private int mMaxImageHeight = 0;
    private int mMaxImageWidth = 0;
    private static RequestQueue queue = null;

    /**
     * Creates an ImageLoader with Bitmap memory cache. No default placeholder
     * image will be shown while the image is being fetched and loaded.
     */
    public ExImageLoader(Context context, ImageCache cache) {
        super(Volley.newRequestQueue(context), cache);
        mResources = context.getResources();
        mPlaceHolderDrawables = new ArrayList<Drawable>(1);
        mPlaceHolderDrawables.add(null);
    }

    /**
     * Creates an ImageLoader with Bitmap memory cache and a default placeholder
     * image while the image is being fetched and loaded.
     */
    public ExImageLoader(Context context, ImageCache cache, int defaultPlaceHolderResId) {
        super(Volley.newRequestQueue(context), cache);
        mResources = context.getResources();
        mPlaceHolderDrawables = new ArrayList<Drawable>(1);
        mPlaceHolderDrawables.add(defaultPlaceHolderResId == -1 ? null : mResources
                .getDrawable(defaultPlaceHolderResId));
    }

    /**
     * Creates an ImageLoader with Bitmap memory cache and a list of default
     * placeholder drawables.
     */
    public ExImageLoader(Context context, ImageCache cache,
            ArrayList<Drawable> placeHolderDrawables) {
        super(Volley.newRequestQueue(context), cache);
        mResources = context.getResources();
        mPlaceHolderDrawables = placeHolderDrawables;
    }

    public ExImageLoader setFadeInImage(boolean fadeInImage) {
        mFadeInImage = fadeInImage;
        return this;
    }

    public ExImageLoader setMaxImageSize(int maxImageWidth, int maxImageHeight) {
        mMaxImageWidth = maxImageWidth;
        mMaxImageHeight = maxImageHeight;
        return this;
    }

    public ExImageLoader setMaxImageSize(int maxImageSize) {
        return setMaxImageSize(maxImageSize, maxImageSize);
    }

    public ImageContainer get(String requestUrl, ImageView imageView) {
        return get(requestUrl, imageView, 0);
    }

    public ImageContainer get(String requestUrl, ImageView imageView, int placeHolderIndex) {
        return get(requestUrl, imageView, mPlaceHolderDrawables.get(placeHolderIndex),
                mMaxImageWidth, mMaxImageHeight);
    }

    public ImageContainer get(String requestUrl, ImageView imageView, Drawable placeHolder) {
        return get(requestUrl, imageView, placeHolder, mMaxImageWidth, mMaxImageHeight);
    }

    public ImageContainer get(String requestUrl, ImageView imageView, Drawable placeHolder,
            int maxWidth, int maxHeight) {

        // 当这个view被复用，查找是否有旧的图片加载请求绑定到这个ImageView
        ImageContainer imageContainer = imageView.getTag() != null
                && imageView.getTag() instanceof ImageContainer ? (ImageContainer) imageView
                .getTag() : null;

        // 找到之前请求的图片地址
        String recycledImageUrl = imageContainer != null ? imageContainer.getRequestUrl() : null;

        // 如果新的图片地址为null，或者跟之前的请求地址不同
        if (requestUrl == null || !requestUrl.equals(recycledImageUrl)) {
            if (imageContainer != null) {
                // 取消之前的请求
                imageContainer.cancelRequest();
                imageView.setTag(null);
            }
            if (requestUrl != null) {
                // 启动一个新的Request去请求新的地址
                imageContainer = get(requestUrl,
                        getImageListener(mResources, imageView, placeHolder, mFadeInImage),
                        maxWidth, maxHeight);
                // 把Request作为tag存入对应的ImageView
                imageView.setTag(imageContainer);
            } else {
                imageView.setImageDrawable(placeHolder);
                imageView.setTag(null);
            }
        }

        return imageContainer;
    }

    private static ImageListener getImageListener(final Resources resources,
            final ImageView imageView,
            final Drawable placeHolder, final boolean fadeInImage) {
        return new ImageListener() {
            @Override
            public void onResponse(ImageContainer response, boolean isImmediate) {
                imageView.setTag(null);
                if (response.getBitmap() != null) {
                    setImageBitmap(imageView, response.getBitmap(), resources, fadeInImage
                            && !isImmediate);
                } else {
                    imageView.setImageDrawable(placeHolder);
                }
            }

            @Override
            public void onErrorResponse(VolleyError volleyError) {
            }
        };
    }

    /**
     * Sets a {@link android.graphics.Bitmap} to an
     * {@link android.widget.ImageView} using a fade-in animation. If there is a
     * {@link android.graphics.drawable.Drawable} already set on the ImageView
     * then use that as the image to fade from. Otherwise fade in from a
     * transparent Drawable.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR1)
    private static void setImageBitmap(final ImageView imageView, final Bitmap bitmap,
            Resources resources,
            boolean fadeIn) {

        // 如果设置了淡入效果并且不低于12版本
        if (fadeIn && hasHoneycombMR1()) {
            // 使用ViewPropertyAnimator实现一个淡入淡出的效果，用于IamgeView的显示。
            imageView.animate().scaleY(0.95f).scaleX(0.95f).alpha(0f)
                    .setDuration(imageView.getDrawable() == null ? 0 : HALF_FADE_IN_TIME)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            imageView.setImageBitmap(bitmap);
                            imageView.animate().alpha(1f).scaleY(1f).scaleX(1f)
                                    .setDuration(HALF_FADE_IN_TIME)
                                    .setListener(null);
                        }
                    });
        } else if (fadeIn) {
            // 否则就用一个TransitionDrawable实现淡入效果
            Drawable initialDrawable;
            if (imageView.getDrawable() != null) {
                initialDrawable = imageView.getDrawable();
            } else {
                initialDrawable = transparentDrawable;
            }
            BitmapDrawable bitmapDrawable = new BitmapDrawable(resources, bitmap);
            // Use TransitionDrawable to fade in
            final TransitionDrawable td = new TransitionDrawable(new Drawable[] {
                    initialDrawable, bitmapDrawable
            });
            imageView.setImageDrawable(td);
            td.startTransition(ANIMATION_FADE_IN_TIME);
        } else {
            // 直接设置图片，不需要效果
            imageView.setImageBitmap(bitmap);
        }
    }

    /**
     * 提供一个接口，可以让activity实现，提供一个ImageLoader给他包含的fragment Interface an activity
     * can implement to provide an ImageLoader to its children fragments.
     */
    public interface ImageLoaderProvider {
        public ExImageLoader getImageLoaderInstance();
    }

    public static boolean hasHoneycomb() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB;
    }

    public static boolean hasHoneycombMR1() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1;
    }

}

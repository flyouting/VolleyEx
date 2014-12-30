
package com.android.volley.request;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.Response.ProgressListener;
import com.android.volley.toolbox.HttpHeaderParser;

/**
 * 通过一个url获取图片数据，返回值为byte[]
 * 
 * @author coffee
 */
public class OtherImageRequest extends Request<byte[]> implements ProgressListener {
    /** 请求超时时间 */
    private static final int IMAGE_TIMEOUT_MS = 2000;

    /** 重试次数 */
    private static final int IMAGE_MAX_RETRIES = 2;

    /** 超时时间的乘数，重试时才用到 */
    private static final float IMAGE_BACKOFF_MULT = 2f;

    private final Response.Listener<byte[]> mListener;

    private ProgressListener mProgressListener;

    /**
     * Creates a new image request
     * 
     * @param url URL of the image
     * @param listener Listener to receive
     * @param errorListener Error listener, or null to ignore errors
     */
    public OtherImageRequest(String url, Response.Listener<byte[]> listener,
            Response.ErrorListener errorListener) {
        super(Method.GET, url, errorListener);
        setRetryPolicy(new DefaultRetryPolicy(IMAGE_TIMEOUT_MS, IMAGE_MAX_RETRIES,
                IMAGE_BACKOFF_MULT));
        mListener = listener;

    }

    public void setOnProgressListener(ProgressListener listener) {
        mProgressListener = listener;
    }

    @Override
    public Priority getPriority() {
        return Priority.LOW;
    }

    @Override
    protected Response<byte[]> parseNetworkResponse(NetworkResponse response) {
        return Response.success(response.data, HttpHeaderParser.parseCacheHeaders(response));
    }

    @Override
    protected void deliverResponse(byte[] response) {
        mListener.onResponse(response);
    }

    @Override
    public void onProgress(long transferredBytes, long totalSize) {
        if (null != mProgressListener) {
            mProgressListener.onProgress(transferredBytes, totalSize);
        }
    }

}

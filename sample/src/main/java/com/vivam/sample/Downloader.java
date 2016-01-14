package com.vivam.sample;

import android.app.ActivityManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.util.Log;

import com.vivam.downloadprovider.DownloadManager;

import java.io.File;
import java.util.Map;

/**
 * Created by vivam on 1/13/16.
 */
public class Downloader {

    private static final String LOG_TAG = "Downloader";

    private static final int MB = 1048576;

    private DownloadManager mManager;

    private DownloadLruCache mCache;

    private Callback mCallback;

    public Downloader(Context context, Callback callback) {
        mManager = new DownloadManager(context.getContentResolver(), BuildConfig.APPLICATION_ID);

        int memClass = ((ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE))
                .getMemoryClass();
        mCache = DownloadLruCache.getInstance(memClass * MB / 8);
        mCallback = callback;
    }

    public Uri getUriForDownloadedFile(long downloadId) {
        return mManager.getUriForDownloadedFile(downloadId);
    }

    public void download(final String url) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);

        long downloadId = mManager.enqueue(request);

        if (downloadId > 0) {
            put(downloadId, url);
            if (mCallback != null) {
                mCallback.onDownloadStarted(downloadId);
            }
        } else {
            if (mCallback != null) {
                mCallback.onDownloadFailed();
            }
        }
    }

    public void pause(final long downloadId) {
        try {
            mManager.pauseDownload(downloadId);
            if (mCallback != null) {
                mCallback.onDownloadPaused();
            }

        } catch (IllegalArgumentException e) {
            Log.e(LOG_TAG, "Can only pause a running download: " + downloadId);
        }
    }

    public void resume(final long downloadId) {
        try {
            mManager.resumeDownload(downloadId);
            if (mCallback  != null) {
                mCallback.onDownloadResumed();
            }
        } catch (IllegalArgumentException e) {
            Log.e(LOG_TAG, "Can only resume a paused download: " + downloadId);
        }
    }

    public void cancel(final long... downloadIds) {
        for (long id : downloadIds) {
            Uri uri = mManager.getUriForDownloadedFile(id);
            deleteFile(uri);
            mManager.remove(downloadIds);
            if (mCallback != null) {
                mCallback.onDownloadCanceled(id);
            }
            remove(id);
        }
    }

    public Map<Long, String> getSnapshot() {
        return mCache.snapshot();
    }

    public String get(long id) {
        return mCache.get(id);
    }

    public String pop(long id) {
        String url = get(id);
        mCache.remove(id);
        return url;
    }

    public void put(long key, String value) {
        mCache.put(key, value);
    }

    public void remove(long key) {
        mCache.remove(key);
    }

    public long[] getBytesAndState(long downloadId) {
        return getLongs(downloadId,
                DownloadManager.COLUMN_TOTAL_SIZE_BYTES,
                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR,
                DownloadManager.COLUMN_STATUS);
    }

    private long[] getLongs(long downloadId, @NonNull String... columnNames) {
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        long[] result = new long[columnNames.length];
        Cursor c = null;
        try {
            c = mManager.query(query);
            if (c != null && c.moveToFirst()) {
                for (int i = 0; i < columnNames.length; i++) {
                    result[i] = c.getLong(c.getColumnIndexOrThrow(columnNames[i]));
                }
            }
        } finally {
            if (c != null) c.close();
        }

        return result;
    }

    public boolean deleteFile(Uri uri) {
        if (uri == null) {
            return true;
        }
        File file = new File(uri.getPath());
        if (file.exists()) {
            return file.delete();
        }
        return true;
    }

    public interface Callback {

        void onDownloadStarted(long downloadId);

        void onDownloadFailed();

        void onDownloadPaused();

        void onDownloadResumed();

        void onDownloadCanceled(long downloadId);
    }
}

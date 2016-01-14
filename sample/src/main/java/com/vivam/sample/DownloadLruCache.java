package com.vivam.sample;

import android.util.LruCache;

/**
 * Created by vivam on 1/13/16.
 */
public class DownloadLruCache extends LruCache<Long, String> {

    private static DownloadLruCache instance;

    public static DownloadLruCache getInstance(int maxSize) {
        if (instance == null) {
            instance = new DownloadLruCache(maxSize);
        }
        return instance;
    }

    private DownloadLruCache(int maxSize) {
        super(maxSize);
    }
}

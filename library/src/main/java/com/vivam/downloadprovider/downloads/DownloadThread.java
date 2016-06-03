/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.vivam.downloadprovider.downloads;

import android.content.ContentValues;
import android.content.Context;
import android.drm.DrmManagerClient;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.WorkSource;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.vivam.downloadprovider.Utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SyncFailedException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;

import static android.text.format.DateUtils.SECOND_IN_MILLIS;
import static com.vivam.downloadprovider.downloads.Constants.TAG;
import static com.vivam.downloadprovider.downloads.Downloads.STATUS_BAD_REQUEST;
import static com.vivam.downloadprovider.downloads.Downloads.STATUS_CANNOT_RESUME;
import static com.vivam.downloadprovider.downloads.Downloads.STATUS_HTTP_DATA_ERROR;
import static com.vivam.downloadprovider.downloads.Downloads.STATUS_TOO_MANY_REDIRECTS;
import static com.vivam.downloadprovider.downloads.Downloads.STATUS_WAITING_FOR_NETWORK;
import static com.vivam.downloadprovider.downloads.Downloads.STATUS_WAITING_TO_RETRY;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PARTIAL;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;

/**
 * Task which executes a given {@link DownloadInfo}: making network requests,
 * persisting data to disk, and updating {@link DownloadProvider}.
 */
class DownloadThread implements Runnable {

    // TODO: bind each download to a specific network interface to avoid state
    // checking races once we have ConnectivityManager API

    private static final int HTTP_REQUESTED_RANGE_NOT_SATISFIABLE = 416;
    private static final int HTTP_TEMP_REDIRECT = 307;

    private static final int DEFAULT_TIMEOUT = (int) (20 * SECOND_IN_MILLIS);

    private final Context mContext;
    private final DownloadInfo mInfo;
    private final SystemFacade mSystemFacade;
    private final StorageManager mStorageManager;
    private final DownloadNotifier mNotifier;

    public DownloadThread(Context context, SystemFacade systemFacade, DownloadInfo info,
                          StorageManager storageManager, DownloadNotifier notifier) {
        mContext = context;
        mSystemFacade = systemFacade;
        mStorageManager = storageManager;
        mInfo = info;
        mNotifier = notifier;
    }

    /**
     * Returns the user agent provided by the initiating app, or use the default one
     */
    private String userAgent() {
        String userAgent = mInfo.mUserAgent;
        if (userAgent == null) {
            userAgent = Constants.DEFAULT_USER_AGENT;
        }
        return userAgent;
    }

    /**
     * State for the entire run() method.
     */
    static class State {
        public String mFilename;
        public OutputStream mStream;
        public String mMimeType;
        public int mRetryAfter = 0;
        public boolean mGotData = false;
        public String mRequestUri;
        public long mTotalBytes = -1;
        public long mCurrentBytes = 0;
        public String mHeaderETag;
        public boolean mContinuingDownload = false;
        public long mBytesNotified = 0;
        public long mTimeLastNotification = 0;
        public int mNetworkType = -1;   // ConnectivityManager.TYPE_NONE

        /** Historical bytes/second speed of this download. */
        public long mSpeed;
        /** Time when current sample started. */
        public long mSpeedSampleStart;
        /** Bytes transferred since current sample started. */
        public long mSpeedSampleBytes;

        public long mContentLength = -1;
        public String mContentDisposition;
        public String mContentLocation;

        public int mRedirectionCount;
        public URL mUrl;

        public State(DownloadInfo info) {
            mMimeType = Utils.normalizeMimeType(info.mMimeType);
            mRequestUri = info.mUri;
            mFilename = info.mFileName;
            mTotalBytes = info.mTotalBytes;
            mCurrentBytes = info.mCurrentBytes;
        }

        public void resetBeforeExecute() {
            // Reset any state from previous execution
            mContentLength = -1;
            mContentDisposition = null;
            mContentLocation = null;
            mRedirectionCount = 0;
        }
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        runInternal();
    }

    private void runInternal() {
        // Skip when download already marked as finished; this download was
        // probably started again while racing with UpdateThread.
        if (DownloadInfo.queryDownloadStatus(mContext.getContentResolver(), mInfo.mId)
                == Downloads.STATUS_SUCCESS) {
            Log.d(TAG, "Download " + mInfo.mId + " already finished; skipping");
            return;
        }

        State state = new State(mInfo);
        PowerManager.WakeLock wakeLock = null;
        int finalStatus = Downloads.STATUS_UNKNOWN_ERROR;
        int numFailed = mInfo.mNumFailed;
        String errorMsg = null;

        // final NetworkPolicyManager netPolicy = NetworkPolicyManager.from(mContext);
        final PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);

        try {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, Constants.TAG);
            wakeLock.setWorkSource(createWorkSource(mInfo.mUid));
            wakeLock.acquire();

            // while performing download, register for rules updates
            // netPolicy.registerListener(mPolicyListener);

            Log.i(Constants.TAG, "Download " + mInfo.mId + " starting");

            // Remember which network this download started on; used to
            // determine if errors were due to network changes.
            /*final NetworkInfo info = mSystemFacade.getActiveNetworkInfo(mInfo.mUid);
            if (info != null) {
                state.mNetworkType = info.getType();
            }*/
            Integer networkType = mSystemFacade.getActiveNetworkType();
            if (networkType != null) {
                state.mNetworkType = networkType;
            }
            // Network traffic on this thread should be counted against the
            // requesting UID, and is tagged with well-known value.
            // TrafficStats.setThreadStatsTag(TrafficStats.TAG_SYSTEM_DOWNLOAD);
            // TrafficStats.setThreadStatsUid(mInfo.mUid);

            try {
                // TODO: migrate URL sanity checking into client side of API
                state.mUrl = new URL(state.mRequestUri);
            } catch (MalformedURLException e) {
                throw new StopRequestException(STATUS_BAD_REQUEST, e);
            }

            executeDownload(state);

            finalizeDestinationFile(state);
            finalStatus = Downloads.STATUS_SUCCESS;
        } catch (StopRequestException error) {
            // remove the cause before printing, in case it contains PII
            errorMsg = error.getMessage();
            String msg = "Aborting request for download " + mInfo.mId + ": " + errorMsg;
            Log.w(Constants.TAG, msg);
            if (Constants.LOGV) {
                Log.w(Constants.TAG, msg, error);
            }
            finalStatus = error.getFinalStatus();

            // Nobody below our level should request retries, since we handle
            // failure counts at this level.
            /*if (finalStatus == STATUS_WAITING_TO_RETRY) {
                throw new IllegalStateException("Execution should always throw final error codes");
            }*/

            // Some errors should be retryable, unless we fail too many times.
            if (isStatusRetryable(finalStatus)) {
                if (state.mGotData) {
                    numFailed = 1;
                } else {
                    numFailed += 1;
                }

                if (numFailed < Constants.MAX_RETRIES) {
                    Integer networkType = mSystemFacade.getActiveNetworkType();
                    if (networkType != null && networkType == state.mNetworkType) {
                        // Underlying network is still intact, use normal backoff
                        finalStatus = STATUS_WAITING_TO_RETRY;
                    } else {
                        // Network changed, retry on any next available
                        finalStatus = STATUS_WAITING_FOR_NETWORK;
                    }
                }
            }

            // fall through to finally block
        } catch (Throwable ex) {
            errorMsg = ex.getMessage();
            String msg = "Exception for id " + mInfo.mId + ": " + errorMsg;
            Log.w(Constants.TAG, msg, ex);
            finalStatus = Downloads.STATUS_UNKNOWN_ERROR;
            // falls through to the code that reports an error
        } finally {
            /*if (finalStatus == STATUS_SUCCESS) {
                TrafficStats.incrementOperationCount(1);
            }*/

            // TrafficStats.clearThreadStatsTag();
            // TrafficStats.clearThreadStatsUid();

            cleanupDestination(state, finalStatus);
            notifyDownloadCompleted(state, finalStatus, errorMsg, numFailed);

            Log.i(Constants.TAG, "Download " + mInfo.mId + " finished with status "
                    + Downloads.statusToString(finalStatus));

            // netPolicy.unregisterListener(mPolicyListener);

            if (wakeLock != null) {
                wakeLock.release();
                wakeLock = null;
            }
        }
        mStorageManager.incrementNumDownloadsSoFar();
    }

    /**
     * Fully execute a single download request. Setup and send the request,
     * handle the response, and transfer the data to the destination file.
     */
    private void executeDownload(State state) throws StopRequestException {
        state.resetBeforeExecute();
        setupDestinationFile(state);

        // skip when already finished; remove after fixing race in 5217390
        if (state.mCurrentBytes == state.mTotalBytes) {
            Log.i(Constants.TAG, "Skipping initiating request for download " +
                    mInfo.mId + "; already completed");
            return;
        }

        while (state.mRedirectionCount++ < Constants.MAX_REDIRECTS) {
            // Open connection and follow any redirects until we have a useful
            // response with body.
            HttpURLConnection conn = null;
            try {
                checkConnectivity();

                conn = (HttpURLConnection) state.mUrl.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(DEFAULT_TIMEOUT);
                conn.setReadTimeout(DEFAULT_TIMEOUT);

                addRequestHeaders(state, conn);

                final int responseCode = conn.getResponseCode();
                switch (responseCode) {
                    case HTTP_OK:
                        if (state.mContinuingDownload) {
                            throw new StopRequestException(
                                    STATUS_CANNOT_RESUME, "Expected partial, but received OK");
                        }
                        processResponseHeaders(state, conn);
                        transferData(state, conn);
                        return;

                    case HTTP_PARTIAL:
                        if (!state.mContinuingDownload) {
                            throw new StopRequestException(
                                    STATUS_CANNOT_RESUME, "Expected OK, but received partial");
                        }
                        transferData(state, conn);
                        return;

                    case HTTP_MOVED_PERM:
                    case HTTP_MOVED_TEMP:
                    case HTTP_SEE_OTHER:
                    case HTTP_TEMP_REDIRECT:
                        final String location = conn.getHeaderField("Location");
                        state.mUrl = new URL(state.mUrl, location);
                        if (responseCode == HTTP_MOVED_PERM) {
                            // Push updated URL back to database
                            state.mRequestUri = state.mUrl.toString();
                        }
                        continue;

                    case HTTP_REQUESTED_RANGE_NOT_SATISFIABLE:
                        throw new StopRequestException(
                                STATUS_CANNOT_RESUME, "Requested range not satisfiable");

                    case HTTP_UNAVAILABLE:
                        parseRetryAfterHeaders(state, conn);
                        throw new StopRequestException(
                                HTTP_UNAVAILABLE, conn.getResponseMessage());

                    case HTTP_INTERNAL_ERROR:
                        throw new StopRequestException(
                                HTTP_INTERNAL_ERROR, conn.getResponseMessage());

                    default:
                        StopRequestException.throwUnhandledHttpError(
                                responseCode, conn.getResponseMessage());
                }
            } catch (IOException e) {
                // Trouble with low-level sockets
                throw new StopRequestException(STATUS_HTTP_DATA_ERROR, e);

            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        throw new StopRequestException(STATUS_TOO_MANY_REDIRECTS, "Too many redirects");
    }

    /**
     * Transfer data from the given connection to the destination file.
     */
    private void transferData(State state, HttpURLConnection conn) throws StopRequestException {
        DrmManagerClient drmClient = null;
        InputStream in = null;
        try {
            try {
                in = conn.getInputStream();
            } catch (IOException e) {
                throw new StopRequestException(STATUS_HTTP_DATA_ERROR, e);
            }

            if (state.mStream == null) {
                try {
                    state.mStream = new FileOutputStream(state.mFilename, true);
                } catch (IOException e) {
                    throw new StopRequestException(STATUS_HTTP_DATA_ERROR, e);
                }
            }

            // Start streaming data, periodically watch for pause/cancel
            // commands and checking disk space as needed.
            transferData(state, in);

        } finally {
            closeDestination(state);
        }
    }

    /**
     * Transfer as much data as possible from the HTTP response to the
     * destination file.
     */
    private void transferData(State state, InputStream in) throws StopRequestException {
        final byte data[] = new byte[Constants.BUFFER_SIZE];
        for (;;) {
            int bytesRead = readFromResponse(state, data, in);
            if (bytesRead == -1) { // success, end of stream already reached
                handleEndOfStream(state);
                return;
            }

            state.mGotData = true;
            writeDataToDestination(state, data, bytesRead);
            state.mCurrentBytes += bytesRead;
            reportProgress(state);

            if (Constants.LOGVV) {
                Log.v(Constants.TAG, "downloaded " + state.mCurrentBytes + " for "
                        + mInfo.mUri);
            }

            checkPausedOrCanceled(state);
        }
    }

    /**
     * Check if current connectivity is valid for this request.
     */
    private void checkConnectivity() throws StopRequestException {
        // checking connectivity will apply current policy
        // mPolicyDirty = false;
        final int networkUsable = mInfo.checkCanUseNetwork();
        if (networkUsable != DownloadInfo.NETWORK_OK) {
            int status = Downloads.STATUS_WAITING_FOR_NETWORK;
            if (networkUsable == DownloadInfo.NETWORK_UNUSABLE_DUE_TO_SIZE) {
                status = Downloads.STATUS_QUEUED_FOR_WIFI;
                mInfo.notifyPauseDueToSize(true);
            } else if (networkUsable == DownloadInfo.NETWORK_RECOMMENDED_UNUSABLE_DUE_TO_SIZE) {
                status = Downloads.STATUS_QUEUED_FOR_WIFI;
                mInfo.notifyPauseDueToSize(false);
            }
            throw new StopRequestException(status,
                    mInfo.getLogMessageForNetworkError(networkUsable));
        }
    }

    /**
     * Called after a successful completion to take any necessary action on the downloaded file.
     */
    private void finalizeDestinationFile(State state) {
        syncDestination(state);
    }

    /**
     * Sync the destination file to storage.
     */
    private void syncDestination(State state) {
        FileOutputStream downloadedFileStream = null;
        try {
            downloadedFileStream = new FileOutputStream(state.mFilename, true);
            downloadedFileStream.getFD().sync();
        } catch (FileNotFoundException ex) {
            Log.w(Constants.TAG, "file " + state.mFilename + " not found: "
                    + ex);
        } catch (SyncFailedException ex) {
            Log.w(Constants.TAG, "file " + state.mFilename + " sync failed: "
                    + ex);
        } catch (IOException ex) {
            Log.w(Constants.TAG, "IOException trying to sync "
                    + state.mFilename + ": " + ex);
        } catch (RuntimeException ex) {
            Log.w(Constants.TAG, "exception while syncing file: ", ex);
        } finally {
            if (downloadedFileStream != null) {
                try {
                    downloadedFileStream.close();
                } catch (IOException ex) {
                    Log.w(Constants.TAG,
                            "IOException while closing synced file: ", ex);
                } catch (RuntimeException ex) {
                    Log.w(Constants.TAG, "exception while closing file: ", ex);
                }
            }
        }
    }

    /**
     * Called just before the thread finishes, regardless of status, to take any necessary action on
     * the downloaded file.
     */
    private void cleanupDestination(State state, int finalStatus) {
        closeDestination(state);
        if (state.mFilename != null && Downloads.isStatusError(finalStatus)) {
            if (Constants.LOGVV) {
                Log.d(TAG, "cleanupDestination() deleting " + state.mFilename);
            }
            new File(state.mFilename).delete();
            state.mFilename = null;
        }
    }

    private void closeDestination(State state) {
        try {
            if (state.mStream != null){
                state.mStream.close();
                state.mStream = null;
            }
        } catch (IOException e) {
            if (Constants.LOGV) {
                Log.v(Constants.TAG,
                        "exception when closing the file after download : "
                                + e);
            }
        }
    }

    /**
     * Check if the download has been paused or canceled, stopping the request appropriately if it
     * has been.
     */
    private void checkPausedOrCanceled(State state) throws StopRequestException {
        synchronized (mInfo) {
            if (mInfo.mControl == Downloads.CONTROL_PAUSED) {
                throw new StopRequestException(
                        Downloads.STATUS_PAUSED_BY_APP, "download paused by owner");
            }
            if (mInfo.mStatus == Downloads.STATUS_CANCELED || mInfo.mDeleted) {
                throw new StopRequestException(Downloads.STATUS_CANCELED, "download canceled");
            }
        }

        // if policy has been changed, trigger connectivity check
        /*if (mPolicyDirty) {
            checkConnectivity();
        }*/
    }

    /**
     * Report download progress through the database if necessary.
     */
    private void reportProgress(State state) {
        final long now = SystemClock.elapsedRealtime();

        // TODO: notify speed
        /*final long sampleDelta = now - state.mSpeedSampleStart;
        if (sampleDelta > 500) {
            final long sampleSpeed = ((state.mCurrentBytes - state.mSpeedSampleBytes) * 1000)
                    / sampleDelta;

            if (state.mSpeed == 0) {
                state.mSpeed = sampleSpeed;
            } else {
                state.mSpeed = ((state.mSpeed * 3) + sampleSpeed) / 4;
            }

            // Only notify once we have a full sample window
            if (state.mSpeedSampleStart != 0) {
                mNotifier.notifyDownloadSpeed(mInfo.mId, state.mSpeed);
            }

            state.mSpeedSampleStart = now;
            state.mSpeedSampleBytes = state.mCurrentBytes;
        }*/

        if (state.mCurrentBytes - state.mBytesNotified > Constants.MIN_PROGRESS_STEP &&
                now - state.mTimeLastNotification > Constants.MIN_PROGRESS_TIME) {
            ContentValues values = new ContentValues();
            values.put(Downloads.COLUMN_CURRENT_BYTES, state.mCurrentBytes);
            mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), values, null, null);
            state.mBytesNotified = state.mCurrentBytes;
            state.mTimeLastNotification = now;
        }
    }

    /**
     * Write a data buffer to the destination file.
     * @param data buffer containing the data to write
     * @param bytesRead how many bytes to write from the buffer
     */
    private void writeDataToDestination(State state, byte[] data, int bytesRead)
            throws StopRequestException {
        mStorageManager.verifySpaceBeforeWritingToFile(
                mInfo.mDestination, state.mFilename, bytesRead);

        boolean forceVerified = false;
        while (true) {
            try {
                if (state.mStream == null) {
                    state.mStream = new FileOutputStream(state.mFilename, true);
                }
                state.mStream.write(data, 0, bytesRead);
                if (mInfo.mDestination == Downloads.DESTINATION_EXTERNAL) {
                    closeDestination(state);
                }
                return;
            } catch (IOException ex) {
                // TODO: better differentiate between DRM and disk failures
                if (!forceVerified) {
                    // couldn't write to file. are we out of space? check.
                    mStorageManager.verifySpace(mInfo.mDestination, state.mFilename, bytesRead);
                    forceVerified = true;
                } else {
                    throw new StopRequestException(Downloads.STATUS_FILE_ERROR,
                            "Failed to write data: " + ex);
                }
                /*if (!Helpers.isExternalMediaMounted()) {
                    throw new StopRequestException(
                            Downloads.STATUS_DEVICE_NOT_FOUND_ERROR,
                            "external media not mounted while writing destination file");
                }

                long availableBytes = Helpers.getAvailableBytes(
                        Helpers.getFilesystemRoot(state.mFilename));
                if (availableBytes < bytesRead) {
                    throw new StopRequestException(
                            Downloads.STATUS_INSUFFICIENT_SPACE_ERROR,
                            "insufficient space while writing destination file",
                            ex);
                }
                throw new StopRequestException(Downloads.STATUS_FILE_ERROR,
                        "Failed to write data: " + ex);*/
            }
        }
    }

    /**
     * Called when we've reached the end of the HTTP response stream, to update the database and
     * check for consistency.
     */
    private void handleEndOfStream(State state) throws StopRequestException {
        ContentValues values = new ContentValues();
        values.put(Downloads.COLUMN_CURRENT_BYTES, state.mCurrentBytes);
        if (state.mContentLength == -1) {
            values.put(Downloads.COLUMN_TOTAL_BYTES, state.mCurrentBytes);
        }
        mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), values, null, null);

        final boolean lengthMismatched = (state.mContentLength != -1)
                && (state.mCurrentBytes != state.mContentLength);
        if (lengthMismatched) {
            if (cannotResume(state)) {
                throw new StopRequestException(STATUS_CANNOT_RESUME,
                        "mismatched content length; unable to resume");
            } else {
                throw new StopRequestException(STATUS_HTTP_DATA_ERROR,
                        "closed socket before end of file");
            }
        }
    }

    private boolean cannotResume(State state) {
        return state.mCurrentBytes > 0 && !mInfo.mNoIntegrity
                && state.mHeaderETag == null;
    }

    /**
     * Read some data from the HTTP response stream, handling I/O errors.
     * @param data buffer to use to read data
     * @param entityStream stream for reading the HTTP response entity
     * @return the number of bytes actually read or -1 if the end of the stream has been reached
     */
    private int readFromResponse(State state, byte[] data, InputStream entityStream)
            throws StopRequestException {
        try {
            return entityStream.read(data);
        } catch (IOException ex) {
            // TODO: handle stream errors the same as other retries
            /*if ("unexpected end of stream".equals(ex.getMessage())) {
                return -1;
            }*/

            ContentValues values = new ContentValues();
            values.put(Downloads.COLUMN_CURRENT_BYTES, state.mCurrentBytes);
            mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), values, null, null);
            if (cannotResume(state)) {
                throw new StopRequestException(STATUS_CANNOT_RESUME,
                        "Failed reading response: " + ex + "; unable to resume", ex);
            } else {
                throw new StopRequestException(STATUS_HTTP_DATA_ERROR,
                        "Failed reading response: " + ex, ex);
            }
        }
    }

    /*private int getFinalStatusForHttpError(State state) {
        if (!Helpers.isNetworkAvailable(mSystemFacade)) {
            return Downloads.STATUS_WAITING_FOR_NETWORK;
        }
        if (mInfo.mNumFailed < Constants.MAX_RETRIES) {
            state.mCountRetry = true;
            return Downloads.STATUS_WAITING_TO_RETRY;
        } else {
            Log.w(Constants.TAG, "reached max retries for " + mInfo.mId);
            return Downloads.STATUS_HTTP_DATA_ERROR;
        }
    }*/

    /**
     * Prepare target file based on given network response. Derives filename and
     * target size as needed.
     */
    private void processResponseHeaders(State state, HttpURLConnection conn)
            throws StopRequestException {
        // TODO: fallocate the entire file if header gave us specific length

        if (state.mContinuingDownload) {
            // ignore response headers on resume requests
            return;
        }

        readResponseHeaders(state, conn);

        state.mFilename = Helpers.generateSaveFile(
                mContext,
                mInfo.mUri,
                mInfo.mHint,
                state.mContentDisposition,
                state.mContentLocation,
                state.mMimeType,
                mInfo.mDestination,
                state.mContentLength,
                mStorageManager);

        try {
            state.mStream = new FileOutputStream(state.mFilename, true);
        } catch (FileNotFoundException e) {
            throw new StopRequestException(Downloads.STATUS_FILE_ERROR,
                    "while opening destination file: " + e.toString(), e);
        }

        updateDatabaseFromHeaders(state);
        // check connectivity again now that we know the total size
        checkConnectivity();
    }

    /**
     * Update necessary database fields based on values of HTTP response headers that have been
     * read.
     */
    private void updateDatabaseFromHeaders(State state) {
        ContentValues values = new ContentValues();
        values.put(Downloads._DATA, state.mFilename);
        if (state.mHeaderETag != null) {
            values.put(Constants.ETAG, state.mHeaderETag);
        }
        if (state.mMimeType != null) {
            values.put(Downloads.COLUMN_MIME_TYPE, state.mMimeType);
        }
        values.put(Downloads.COLUMN_TOTAL_BYTES, mInfo.mTotalBytes);
        mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), values, null, null);
    }

    /**
     * Read headers from the HTTP response and store them into local state.
     */
    private void readResponseHeaders(State state, HttpURLConnection conn)
            throws StopRequestException {
        state.mContentDisposition = conn.getHeaderField("Content-Disposition");
        state.mContentLocation = conn.getHeaderField("Content-Location");

        if (state.mMimeType == null) {
            state.mMimeType = Utils.normalizeMimeType(conn.getContentType());
        }

        state.mHeaderETag = conn.getHeaderField("ETag");

        final String transferEncoding = conn.getHeaderField("Transfer-Encoding");
        if (transferEncoding == null) {
            state.mContentLength = getHeaderFieldLong(conn, "Content-Length", -1);
        } else {
            Log.i(TAG, "Ignoring Content-Length since Transfer-Encoding is also defined");
            state.mContentLength = -1;
        }

        state.mTotalBytes = state.mContentLength;
        mInfo.mTotalBytes = state.mContentLength;

        final boolean noSizeInfo = state.mContentLength == -1
                && (transferEncoding == null || !transferEncoding.equalsIgnoreCase("chunked"));
        if (!mInfo.mNoIntegrity && noSizeInfo) {
            throw new StopRequestException(STATUS_CANNOT_RESUME,
                    "can't know size of download, giving up");
        }
    }

    private void parseRetryAfterHeaders(State state, HttpURLConnection conn) {
        state.mRetryAfter = conn.getHeaderFieldInt("Retry-After", -1);
        if (state.mRetryAfter < 0) {
            state.mRetryAfter = 0;
        } else {
            if (state.mRetryAfter < Constants.MIN_RETRY_AFTER) {
                state.mRetryAfter = Constants.MIN_RETRY_AFTER;
            } else if (state.mRetryAfter > Constants.MAX_RETRY_AFTER) {
                state.mRetryAfter = Constants.MAX_RETRY_AFTER;
            }
            state.mRetryAfter += Helpers.sRandom.nextInt(Constants.MIN_RETRY_AFTER + 1);
            state.mRetryAfter *= 1000;
        }
    }

    /**
     * Prepare the destination file to receive data.  If the file already exists, we'll set up
     * appropriately for resumption.
     */
    private void setupDestinationFile(State state) throws StopRequestException {
        if (!TextUtils.isEmpty(state.mFilename)) { // only true if we've already run a thread for this download
            if (Constants.LOGV) {
                Log.i(Constants.TAG, "have run thread before for id: " + mInfo.mId +
                        ", and state.mFilename: " + state.mFilename);
            }
            if (!Helpers.isFilenameValid(state.mFilename,
                    mStorageManager.getDownloadDataDirectory())) {
                // this should never happen
                throw new StopRequestException(Downloads.STATUS_FILE_ERROR,
                        "found invalid internal destination filename");
            }
            // We're resuming a download that got interrupted
            File f = new File(state.mFilename);
            if (f.exists()) {
                if (Constants.LOGV) {
                    Log.i(Constants.TAG, "resuming download for id: " + mInfo.mId +
                            ", and state.mFilename: " + state.mFilename);
                }
                long fileLength = f.length();
                if (fileLength == 0) {
                    // The download hadn't actually started, we can restart from scratch
                    if (Constants.LOGVV) {
                        Log.d(TAG, "setupDestinationFile() found fileLength=0, deleting "
                                + state.mFilename);
                    }
                    f.delete();
                    state.mFilename = null;
                    if (Constants.LOGV) {
                        Log.i(Constants.TAG, "resuming download for id: " + mInfo.mId +
                                ", BUT starting from scratch again: ");
                    }
                } else if (mInfo.mETag == null && !mInfo.mNoIntegrity) {
                    // This should've been caught upon failure
                    if (Constants.LOGVV) {
                        Log.d(TAG, "setupDestinationFile() unable to resume download, deleting "
                                + state.mFilename);
                    }
                    f.delete();
                    throw new StopRequestException(Downloads.STATUS_CANNOT_RESUME,
                            "Trying to resume a download that can't be resumed");
                } else {
                    // All right, we'll be able to resume this download
                    if (Constants.LOGV) {
                        Log.i(Constants.TAG, "resuming download for id: " + mInfo.mId +
                                ", and starting with file of length: " + fileLength);
                    }
                    try {
                        state.mStream = new FileOutputStream(state.mFilename, true);
                    } catch (FileNotFoundException e) {
                        throw new StopRequestException(Downloads.STATUS_FILE_ERROR,
                                "while opening destination for resuming: " + e.toString(), e);
                    }
                    state.mCurrentBytes = (int) fileLength;
                    if (mInfo.mTotalBytes != -1) {
                        state.mContentLength = mInfo.mTotalBytes;
                    }
                    state.mHeaderETag = mInfo.mETag;
                    state.mContinuingDownload = true;
                    if (Constants.LOGV) {
                        Log.i(Constants.TAG, "resuming download for id: " + mInfo.mId +
                                ", state.mCurrentBytes: " + state.mCurrentBytes +
                                ", and setting mContinuingDownload to true: ");
                    }
                }
            }
        }

        if (state.mStream != null && mInfo.mDestination == Downloads.DESTINATION_EXTERNAL) {
            closeDestination(state);
        }
    }

    /**
     * Add custom headers for this download to the HTTP request.
     */
    private void addRequestHeaders(State state, HttpURLConnection conn) {
        for (Pair<String, String> header : mInfo.getHeaders()) {
            conn.addRequestProperty(header.first, header.second);
        }

        // Only splice in user agent when not already defined
        if (conn.getRequestProperty("User-Agent") == null) {
            conn.addRequestProperty("User-Agent", userAgent());
        }

        // Defeat transparent gzip compression, since it doesn't allow us to
        // easily resume partial downloads.
        conn.setRequestProperty("Accept-Encoding", "identity");

        if (state.mContinuingDownload) {
            if (state.mHeaderETag != null) {
                conn.addRequestProperty("If-Match", state.mHeaderETag);
            }
            conn.addRequestProperty("Range", "bytes=" + state.mCurrentBytes + "-");
        }
    }

    /**
     * Stores information about the completed download, and notifies the initiating application.
     */
    private void notifyDownloadCompleted(
            State state, int finalStatus, String errorMsg, int numFailed) {
        notifyThroughDatabase(state, finalStatus, numFailed);
        if (Downloads.isStatusCompleted(finalStatus)) {
            mInfo.sendIntentIfRequested();
        }
    }

    private void notifyThroughDatabase(State state, int finalStatus, int numFailed) {
        ContentValues values = new ContentValues();
        values.put(Downloads.COLUMN_STATUS, finalStatus);
        values.put(Downloads._DATA, state.mFilename);
        values.put(Downloads.COLUMN_MIME_TYPE, state.mMimeType);
        values.put(Downloads.COLUMN_LAST_MODIFICATION, mSystemFacade.currentTimeMillis());
        values.put(Constants.FAILED_CONNECTIONS, numFailed);
        values.put(Constants.RETRY_AFTER_X_REDIRECT_COUNT, state.mRetryAfter);

        if (!TextUtils.equals(mInfo.mUri, state.mRequestUri)) {
            values.put(Downloads.COLUMN_URI, state.mRequestUri);
        }

        mContext.getContentResolver().update(mInfo.getAllDownloadsUri(), values, null, null);
    }

    public static long getHeaderFieldLong(URLConnection conn, String field, long defaultValue) {
        try {
            return Long.parseLong(conn.getHeaderField(field));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Return if given status is eligible to be treated as
     * {@link Downloads#STATUS_WAITING_TO_RETRY}.
     */
    public static boolean isStatusRetryable(int status) {
        switch (status) {
            case STATUS_HTTP_DATA_ERROR:
            case HTTP_UNAVAILABLE:
            case HTTP_INTERNAL_ERROR:
                return true;
            default:
                return false;
        }
    }

    private WorkSource createWorkSource(int uid) {
        Parcel source = Parcel.obtain();
        source.writeInt(1);
        source.writeIntArray(new int[] { uid, 0 });
        return WorkSource.CREATOR.createFromParcel(source);
    }
}

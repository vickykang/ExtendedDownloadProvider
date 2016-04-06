package com.vivam.sample;

import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;

import com.vivam.downloadprovider.DownloadManager;
import com.vivam.downloadprovider.downloads.Downloads;

import java.util.Map;

public class MainActivity extends AppCompatActivity
        implements Downloader.Callback, View.OnClickListener {

    private static final String LOG_TAG = "MainActivity";

    private static final int WHAT_CHANGED = 0;

    private static final String URL = "http://14.18.142.97/m.wdjcdn.com/apk.wdjcdn.com/e/36/14f28a8638f6a5ed976282139cf0436e.apk";

    private static final String TEXT_DOWNLOAD = "下载";
    private static final String TEXT_WAITING = "等待";
    private static final String TEXT_PAUSED = "暂停";
    private static final String TEXT_RESUME = "继续";
    private static final String TEXT_SUCCESS = "完成";

    private static final String ARG_STATUS = "status";
    private static final String ARG_DOWNLOAD_ID = "downloadId";

    private EditText mInputView;
    private Button mDownloadBtn;
    private Button mCancelBtn;
    private ProgressBar mProgressBar;

    private Downloader mDownloader;

    private AlertDialog mDialog;

    private BroadcastReceiver mReceiver;

    private DownloaadObserver mObserver;

    private Handler mHandler;

    private int mStatus = 0;

    private long mDownloadId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mInputView = (EditText) findViewById(R.id.url_input);
        mProgressBar = (ProgressBar) findViewById(R.id.progress);
        mDownloadBtn = (Button) findViewById(R.id.btn_download);
        mCancelBtn = (Button) findViewById(R.id.btn_cancel);

        mDownloader = new Downloader(this, this);

        mReceiver = new DownloadCompletedReceiver();

        mHandler = new Handler(Looper.getMainLooper()) {

            @Override
            public void handleMessage(Message msg) {
                if (msg.what == WHAT_CHANGED) {
                    refresh(mDownloadId);
                }
            }
        };

        mObserver = new DownloaadObserver(mHandler);

        getContentResolver().registerContentObserver(
                Downloads.CONTENT_URI, true, mObserver);

        initData(savedInstanceState);
        mInputView.setText(URL);
        mDownloadBtn.setOnClickListener(this);
        mCancelBtn.setOnClickListener(this);
    }

    private void initData(Bundle savedInstanceState) {

        if (savedInstanceState != null) {
            mStatus = savedInstanceState.getInt(ARG_STATUS, 0);
            mDownloadId = savedInstanceState.getLong(ARG_DOWNLOAD_ID, -1);
        }

        Map<Long, String> cacheData = mDownloader.getSnapshot();
        if (cacheData != null && cacheData.size() > 0) {
            for (Long downloadId : cacheData.keySet()) {
                long[] bytesAndState = mDownloader.getBytesAndState(downloadId);
                long total = bytesAndState[0];
                long downloaded = bytesAndState[1];
                int status = (int) bytesAndState[2];

                if (total > 0) {
                    notifyStatusChanged(status);

                    int progress = (int) ((double) downloaded * 100 / (double) total);
                    mProgressBar.setVisibility(View.VISIBLE);
                    mProgressBar.setProgress(progress);

                    mDownloadId = downloadId;
                    mCancelBtn.setEnabled(true);
                    mProgressBar.setVisibility(View.VISIBLE);
                    notifyStatusChanged(status);
                }

                return;
            }
        }

    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mReceiver, new IntentFilter(
                DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mReceiver);
    }

    private void refresh(long downloadId) {
        long[] bytesAndState = mDownloader.getBytesAndState(downloadId);
        long total = bytesAndState[0];
        long current = bytesAndState[1];
        mStatus = (int) bytesAndState[2];
        Log.d(LOG_TAG, "total=" + total + ", current=" + current + ", status=" + mStatus);

        if (total > 0) {
            if (current < 0) current = 0;
            int progress = (int) ((double) current * 100 / (double) total);
            mProgressBar.setProgress(progress);
        }
        notifyStatusChanged(mStatus);
    }

    private void notifyStatusChanged(int status) {
        switch (status) {

            case DownloadManager.STATUS_PENDING:
                syncButton(TEXT_WAITING, false);
                mCancelBtn.setEnabled(true);
                break;

            case DownloadManager.STATUS_RUNNING:
                syncButton(TEXT_PAUSED, true);
                mCancelBtn.setEnabled(true);
                break;

            case DownloadManager.STATUS_PAUSED:
                syncButton(TEXT_RESUME, true);
                mCancelBtn.setEnabled(true);
                break;

            case DownloadManager.STATUS_SUCCESSFUL:
                syncButton(TEXT_SUCCESS, false);
                mCancelBtn.setText("删除文件");
                mCancelBtn.setEnabled(true);
                break;
        }
    }

    private void syncButton(String text, boolean enable) {
        mDownloadBtn.setText(text);
        mDownloadBtn.setEnabled(enable);
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.btn_download) {
            switch (mStatus) {

                case DownloadManager.STATUS_RUNNING:
                    mDownloader.pause(mDownloadId);
                    break;

                case DownloadManager.STATUS_PAUSED:
                    mDownloader.resume(mDownloadId);
                    break;

                default:
                    mDownloader.download(mInputView.getText().toString());
                    break;
            }
        } else if (v.getId() == R.id.btn_cancel) {
            mDownloader.cancel(mDownloadId);
            mCancelBtn.setText("取消");
        }
    }

    @Override
    public void onDownloadStarted(long downloadId) {
        mDownloadId = downloadId;
        syncButton(TEXT_PAUSED, true);
        mCancelBtn.setEnabled(true);
        mProgressBar.setVisibility(View.VISIBLE);
    }

    @Override
    public void onDownloadFailed() {
        if (mDialog == null) {
            buildDialog();
        }
        mDialog.setMessage("下载失败┭┮﹏┭┮");
        mDialog.show();
    }

    @Override
    public void onDownloadPaused() {
        syncButton(TEXT_RESUME, true);
    }

    @Override
    public void onDownloadResumed() {
        syncButton(TEXT_PAUSED, true);
    }

    @Override
    public void onDownloadCanceled(long downloadId) {
        syncButton(TEXT_DOWNLOAD, true);
        mCancelBtn.setEnabled(false);
        mProgressBar.setVisibility(View.INVISIBLE);
    }

    @Override
    public void onSaveInstanceState(Bundle outState, PersistableBundle outPersistentState) {
        super.onSaveInstanceState(outState, outPersistentState);
        outState.putInt(ARG_STATUS, mStatus);
        outState.putLong(ARG_DOWNLOAD_ID, mDownloadId);
    }

    @Override
    protected void onDestroy() {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        getContentResolver().unregisterContentObserver(mObserver);
        super.onDestroy();
    }

    private void buildDialog() {
        mDialog = new AlertDialog.Builder(MainActivity.this)
                .setTitle("下载结果")
                .setNeutralButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .setIcon(R.drawable.icon_dialog)
                .create();
    }

    private class DownloaadObserver extends ContentObserver {

        private Handler mHandler;

        public DownloaadObserver(Handler handler) {
            super(handler);
            mHandler = handler;
        }

        @Override
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            mHandler.sendMessageDelayed(mHandler.obtainMessage(WHAT_CHANGED), 1000);
        }
    }

    private class DownloadCompletedReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (mDialog == null) {
                buildDialog();
            }

            final long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            int status = (int) mDownloader.getBytesAndState(downloadId)[2];

            switch (status) {
                case DownloadManager.STATUS_SUCCESSFUL: {
                    Uri uri = mDownloader.getUriForDownloadedFile(downloadId);
                    String msg = "下载成功！";
                    if (uri != null) {
                        msg += "\r\n文件保存在:" + uri.getPath();
                    }
                    mDialog.setMessage(msg);
                    mDialog.show();
                    break;
                }

                case DownloadManager.STATUS_FAILED: {
                    mDownloader.remove(downloadId);
                    mDialog.setMessage("下载失败┭┮﹏┭┮");
                    mDialog.show();
                    break;
                }
            }
        }
    }
}

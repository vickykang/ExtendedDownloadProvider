package com.vivam.downloadprovider.downloads;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.collect.Maps;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.text.TextUtils;

import com.vivam.downloadprovider.DownloadManager;
import com.vivam.downloadprovider.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static com.vivam.downloadprovider.DownloadManager.Request.VISIBILITY_VISIBLE;
import static com.vivam.downloadprovider.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED;
import static com.vivam.downloadprovider.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION;

/**
 * Update {@link NotificationManager} to reflect current {@link DownloadInfo}
 * states. Collapses similar downloads into a single notification, and builds
 * {@link PendingIntent} that launch towards {@link DownloadReceiver}.
 */
class DownloadNotifier {
    private static final int TYPE_ACTIVE = 1;
    private static final int TYPE_WAITING = 2;
    private static final int TYPE_COMPLETE = 3;

    private final Context mContext;
    private final NotificationManager mNotifManager;

    /**
     * Currently active notifications, mapped from clustering tag to timestamp
     * when first shown.
     *
     * @see #buildNotificationTag(DownloadInfo)
     */
    //@GuardedBy("mActiveNotifs")
    private final HashMap<String, Long> mActiveNotifs = Maps.newHashMap();

    public DownloadNotifier(Context context) {
        mContext = context;
        mNotifManager = (NotificationManager) mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
    }

    public void cancelAll() {
        mNotifManager.cancelAll();
    }



    /**
     * Update {@link NotificationManager} to reflect the given set of
     * {@link DownloadInfo}, adding, collapsing, and removing as needed.
     */
    public void updateWith(Collection<DownloadInfo> downloads) {
        synchronized (mActiveNotifs) {
            updateWithLocked(downloads);
        }
    }

    private void updateWithLocked(Collection<DownloadInfo> downloads) {
        final Resources res = mContext.getResources();

        // Cluster downloads together
        final Map<String, ArrayList<DownloadInfo>> clustered =
                new HashMap<String, ArrayList<DownloadInfo>>();
        for (DownloadInfo info : downloads) {
            final String tag = buildNotificationTag(info);
            if (tag != null) {
                ArrayList<DownloadInfo> downloadInfos = clustered.get(tag);
                if (downloadInfos == null) {
                    downloadInfos = new ArrayList<DownloadInfo>();
                    clustered.put(tag, downloadInfos);
                }
                downloadInfos.add(info);
            }
        }

        // Build notification for each cluster
        for (String tag : clustered.keySet()) {
            final int type = getNotificationTagType(tag);
            final Collection<DownloadInfo> cluster = clustered.get(tag);

            final Notification.Builder builder = new Notification.Builder(mContext);

            // Use time when cluster was first shown to avoid shuffling
            final long firstShown;
            if (mActiveNotifs.containsKey(tag)) {
                firstShown = mActiveNotifs.get(tag);
            } else {
                firstShown = System.currentTimeMillis();
                mActiveNotifs.put(tag, firstShown);
            }
            builder.setWhen(firstShown);

            // Show relevant icon
            if (type == TYPE_ACTIVE) {
                builder.setSmallIcon(android.R.drawable.stat_sys_download);
            } else if (type == TYPE_WAITING) {
                builder.setSmallIcon(android.R.drawable.stat_sys_warning);
            } else if (type == TYPE_COMPLETE) {
                builder.setSmallIcon(android.R.drawable.stat_sys_download_done);
            }

            // Build action intents
            if (type == TYPE_ACTIVE || type == TYPE_WAITING) {
                // build a synthetic uri for intent identification purposes
                final Uri uri = new Uri.Builder().scheme("active-dl").appendPath(tag).build();
                final Intent intent = new Intent(Constants.ACTION_LIST,
                        uri, mContext, DownloadReceiver.class);
                intent.putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS,
                        getDownloadIds(cluster));
                builder.setContentIntent(PendingIntent.getBroadcast(mContext,
                        0, intent, PendingIntent.FLAG_UPDATE_CURRENT));
                builder.setOngoing(true);

            } else if (type == TYPE_COMPLETE) {
                final DownloadInfo info = cluster.iterator().next();
                final Uri uri = ContentUris.withAppendedId(
                        Downloads.ALL_DOWNLOADS_CONTENT_URI, info.mId);
                builder.setAutoCancel(true);

                final String action;
                if (Downloads.isStatusError(info.mStatus)) {
                    action = Constants.ACTION_LIST;
                } else {
                    if (info.mDestination != Downloads.DESTINATION_SYSTEMCACHE_PARTITION) {
                        action = Constants.ACTION_OPEN;
                    } else {
                        action = Constants.ACTION_LIST;
                    }
                }

                final Intent intent = new Intent(action, uri, mContext, DownloadReceiver.class);
                intent.putExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS,
                        getDownloadIds(cluster));
                builder.setContentIntent(PendingIntent.getBroadcast(mContext,
                        0, intent, PendingIntent.FLAG_UPDATE_CURRENT));

                final Intent hideIntent = new Intent(Constants.ACTION_HIDE,
                        uri, mContext, DownloadReceiver.class);
                builder.setDeleteIntent(PendingIntent.getBroadcast(mContext, 0, hideIntent, 0));
            }

            // Calculate and show progress
            String percentText = null;
            if (type == TYPE_ACTIVE) {
                long current = 0;
                long total = 0;
                for (DownloadInfo info : cluster) {
                    if (info.mTotalBytes != -1) {
                        current += info.mCurrentBytes;
                        total += info.mTotalBytes;
                    }
                }

                if (total > 0) {
                    final int percent = (int) ((current * 100) / total);
                    percentText = res.getString(R.string.download_percent, percent);

                    builder.setProgress(100, percent, false);
                } else {
                    builder.setProgress(100, 0, true);
                }
            }

            // Build titles and description
            final Notification notif;
            if (cluster.size() == 1) {
                final DownloadInfo info = cluster.iterator().next();

                builder.setContentTitle(getDownloadTitle(res, info));

                if (type == TYPE_ACTIVE) {
                    if (!TextUtils.isEmpty(info.mDescription)) {
                        builder.setContentText(info.mDescription);
                    }
                    builder.setContentInfo(percentText);

                } else if (type == TYPE_WAITING) {
                    builder.setContentText(
                            res.getString(R.string.notification_need_wifi_for_size));

                } else if (type == TYPE_COMPLETE) {
                    if (Downloads.isStatusError(info.mStatus)) {
                        builder.setContentText(res.getText(R.string.notification_download_failed));
                    } else if (Downloads.isStatusSuccess(info.mStatus)) {
                        builder.setContentText(
                                res.getText(R.string.notification_download_complete));
                    }
                }

                notif = builder.getNotification();

            } else {
                final Notification.InboxStyle inboxStyle = new Notification.InboxStyle(builder);

                for (DownloadInfo info : cluster) {
                    inboxStyle.addLine(getDownloadTitle(res, info));
                }

                if (type == TYPE_ACTIVE) {
                    builder.setContentTitle(res.getQuantityString(
                            R.plurals.notif_summary_active, cluster.size(), cluster.size()));
                    builder.setContentText("");
                    builder.setContentInfo(percentText);
                    inboxStyle.setSummaryText("");

                } else if (type == TYPE_WAITING) {
                    builder.setContentTitle(res.getQuantityString(
                            R.plurals.notif_summary_waiting, cluster.size(), cluster.size()));
                    builder.setContentText(
                            res.getString(R.string.notification_need_wifi_for_size));
                    inboxStyle.setSummaryText(
                            res.getString(R.string.notification_need_wifi_for_size));
                }

                notif = inboxStyle.build();
            }

            mNotifManager.notify(tag, 0, notif);
        }

        // Remove stale tags that weren't renewed
        final Iterator<String> it = mActiveNotifs.keySet().iterator();
        while (it.hasNext()) {
            final String tag = it.next();
            if (!clustered.containsKey(tag)) {
                mNotifManager.cancel(tag, 0);
                it.remove();
            }
        }
    }

    private static CharSequence getDownloadTitle(Resources res, DownloadInfo info) {
        if (!TextUtils.isEmpty(info.mTitle)) {
            return info.mTitle;
        } else {
            return res.getString(R.string.download_unknown_title);
        }
    }

    private long[] getDownloadIds(Collection<DownloadInfo> infos) {
        final long[] ids = new long[infos.size()];
        int i = 0;
        for (DownloadInfo info : infos) {
            ids[i++] = info.mId;
        }
        return ids;
    }

    /**
     * Build tag used for collapsing several {@link DownloadInfo} into a single
     * {@link Notification}.
     */
    private static String buildNotificationTag(DownloadInfo info) {
        if (info.mStatus == Downloads.STATUS_QUEUED_FOR_WIFI) {
            return TYPE_WAITING + ":" + info.mPackage;
        } else if (isActiveAndVisible(info)) {
            return TYPE_ACTIVE + ":" + info.mPackage;
        } else if (isCompleteAndVisible(info)) {
            // Complete downloads always have unique notifs
            return TYPE_COMPLETE + ":" + info.mId;
        } else {
            return null;
        }
    }

    /**
     * Return the cluster type of the given tag, as created by
     * {@link #buildNotificationTag(DownloadInfo)}.
     */
    private static int getNotificationTagType(String tag) {
        return Integer.parseInt(tag.substring(0, tag.indexOf(':')));
    }

    private static boolean isActiveAndVisible(DownloadInfo download) {
        return download.mStatus == Downloads.STATUS_RUNNING &&
                (download.mVisibility == VISIBILITY_VISIBLE
                        || download.mVisibility == VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
    }

    private static boolean isCompleteAndVisible(DownloadInfo download) {
        return Downloads.isStatusCompleted(download.mStatus) &&
                (download.mVisibility == VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        || download.mVisibility == VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION);
    }
}

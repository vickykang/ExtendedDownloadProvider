# ExtendedDownloadProvider
Porting Android DownloadProvider (based on https://github.com/yxl/DownloadProvider)
#简介
    将Android 系统的DownloadProvider 独立出来，增加了暂停下载、继续下载的功能。本项目目前只支持Android 4.4 及以上。
    本项目参考 https://github.com/yxl/DownloadProvider ，原项目支持Android 2.2 及以上。
    本项目是Android Studio 工程。

#使用
    1. 下载library 模块，添加到自己工程中。
    2.
    获取服务：
    DownloadManager mManager = new DownloadManager(context.getContentResolver(), BuildConfig.APPLICATION_ID);
    其他与系统的DownloadManager类似，不过多了两个方法
    mManager.pauseDownload(downloadId)
    mManager.resumeDownload(downloadId)
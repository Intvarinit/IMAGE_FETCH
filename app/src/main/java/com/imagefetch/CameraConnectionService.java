package com.imagefetch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 会话保活前台服务（M2）：只负责「保住进程优先级 + 在通知栏显示会话状态」，
 * <b>不</b>持有 {@link CameraSessionCoordinator}——协调器仍归 {@code MainActivity}，
 * 避免把整个会话层搬进 Service 的大重构。
 *
 * <p>连接成功后启动，手动断开或退出应用时停止；<b>自动重连期间保持运行</b>，
 * 否则进程一旦被系统回收，退避重连就再也跑不完了。
 *
 * <p>前台服务类型选 {@code dataSync}：本应用主路径是 Wi‑Fi 传图，不满足
 * {@code connectedDevice} 在 API 34+ 要求的蓝牙 / NFC / UWB / USB 前置条件，
 * 用 {@code connectedDevice} 会在 {@code startForeground} 直接抛 SecurityException。
 *
 * <p>Android 13+ 未授予 {@code POST_NOTIFICATIONS} 不影响服务运行，只是通知不展示，
 * 所以权限被拒也不阻断连接。所有入口都吞掉启动异常：最坏结果是丢进程优先级，
 * 绝不会牵连正在跑的会话。
 */
public final class CameraConnectionService extends Service {
    private static final String CHANNEL_ID = "camera_session";
    private static final int NOTIFICATION_ID = 0x51;

    private static final String ACTION_STOP = "com.imagefetch.action.SESSION_STOP";
    private static final String EXTRA_TEXT = "text";

    /** 启动或更新前台服务并刷新通知文案（重复调用安全）。 */
    public static void start(Context context, String text) {
        Intent intent = new Intent(context, CameraConnectionService.class)
                .putExtra(EXTRA_TEXT, text);
        try {
            context.startForegroundService(intent);
        } catch (Exception ignored) {
        }
    }

    /** 停止前台服务（手动断开、退出应用）。 */
    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, CameraConnectionService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        String text = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);
        startForegroundCompat(buildNotification(
                getString(R.string.session_notification_title),
                text == null ? getString(R.string.session_notification_idle) : text));
        return START_NOT_STICKY;
    }

    /**
     * Android 15 的 dataSync 有每日配额，超额时系统回调这里。
     * 自行退出即可：会话降级为「无进程保护」，不断链也不崩。
     */
    @Override
    public void onTimeout(int startId) {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.session_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.session_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception ignored) {
            // 类型前置条件不满足等异常：退化为普通后台服务，会话照常执行。
        }
    }

    private Notification buildNotification(String title, String text) {
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}

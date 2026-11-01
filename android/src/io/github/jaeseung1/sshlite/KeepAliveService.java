package io.github.jaeseung1.sshlite;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * 서버에 연결된 동안 알림 하나를 띄워 두는 서비스.
 * 이게 없으면 다른 앱을 쓰는 사이 안드로이드가 앱을 얼려서(배터리 절약) SSH 연결이 끊김.
 * 알림을 누르면 앱으로 돌아옴. 연결을 끊거나 앱을 닫으면 사라짐.
 */
public class KeepAliveService extends Service {
    static final String CHANNEL = "connection";
    private static final int ID = 1;

    static void start(Context c, String label) {
        Intent i = new Intent(c, KeepAliveService.class).putExtra("label", label);
        try {  // Context.startForegroundService (Android 8+, API 23 기준으로 빌드해서 리플렉션)
            Context.class.getMethod("startForegroundService", Intent.class).invoke(c, i);
        } catch (Exception ignored) {  // 백그라운드에서 시작이 막히는 경우 등: 연결 자체는 그대로
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, KeepAliveService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String label = intent == null ? null : intent.getStringExtra("label");
        startForeground(ID, build(label == null ? "" : label));
        return START_NOT_STICKY;  // 앱이 정리되면 같이 끝남 (연결은 앱 안에 있으니까)
    }

    @SuppressWarnings("deprecation")
    private Notification build(String label) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b = new Notification.Builder(this);
        // Android 8+ 알림 채널 (API 23 기준으로 빌드해서 리플렉션으로 부름)
        try {
            Class<?> ch = Class.forName("android.app.NotificationChannel");
            Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CHANNEL, "서버 연결 유지", 2 /* IMPORTANCE_LOW: 소리·진동 없음 */);
            ch.getMethod("setShowBadge", boolean.class).invoke(channel, false);
            NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, channel);
            Notification.Builder.class.getMethod("setChannelId", String.class).invoke(b, CHANNEL);
        } catch (Exception ignored) {
        }
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int icon = getResources().getIdentifier("ic_stat", "drawable", getPackageName());
        return b.setSmallIcon(icon != 0 ? icon : getApplicationInfo().icon)
                .setContentTitle("서버에 연결돼 있어요")
                .setContentText(label.isEmpty() ? "SSH Lite" : label + " · 다른 앱을 써도 끊기지 않게 유지 중")
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

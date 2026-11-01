package app.pocketremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import app.pocketremote.R;

/**
 * Keeps the laptop connection alive when the app is not on screen, so the phone is already connected (and finds the
 * laptop the moment it switches on) when you pick it up. It shows one quiet notification; "Turn off" there, or the
 * switch on the Control tab, stops it.
 */
public final class BackgroundService extends Service {
    static final String ACTION_STOP = "app.pocketremote.STOP_BACKGROUND";
    private static final String CHANNEL = "laptop-connection";
    private static final int ID = 1;

    private PcLink link;
    private boolean holding;
    private String text = "Looking for the laptop…";
    private final PcLink.Status listener = (t, color) -> {
        text = color == Ui.GREEN ? "Connected to " + link.name() : color == Ui.RED ? t : "Looking for the laptop…";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ID, build());
    };

    @Override
    public void onCreate() {
        super.onCreate();
        link = PcLink.shared(this);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Laptop connection", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            SharedPreferences prefs = getSharedPreferences("pocketremote", MODE_PRIVATE);
            prefs.edit().putBoolean("pcBackground", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(ID, build());
        }
        if (!holding) {
            holding = true;
            link.acquire();
            link.addStatusListener(listener);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (holding) {
            holding = false;
            link.removeStatusListener(listener);
            link.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification build() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent off = PendingIntent.getService(this, 1, new Intent(this, BackgroundService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Pocket Remote")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Turn off", off).build())
                .build();
    }
}

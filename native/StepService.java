package com.antrenman.cizelge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;

/** Yürüyüş oturumu sırasında sensörleri dinleyen ön plan hizmeti. */
public class StepService extends Service implements SensorEventListener {

    static volatile StepService instance;
    static volatile StepEngine engine;
    static volatile long startedAtMs = 0;
    static volatile float hwBase = -1, hwLast = -1;
    static volatile boolean hasDetector, hasCounter, hasAccel, hasGyro;

    private static final String CH = "antrenman_steps";
    private static final int NOTIF_ID = 4711;
    private SensorManager sm;
    private PowerManager.WakeLock wl;
    private boolean started = false;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (started) return START_NOT_STICKY;
        started = true;
        instance = this;
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
        } else {
            startForeground(NOTIF_ID, n);
        }

        StepEngine.Config cfg = new StepEngine.Config();
        if (intent != null) {
            cfg.minBout = intent.getIntExtra("minBout", cfg.minBout);
            cfg.maxCv = intent.getDoubleExtra("maxCv", cfg.maxCv);
            cfg.rhoMin = intent.getDoubleExtra("rhoMin", cfg.rhoMin);
            cfg.gyroMax = intent.getDoubleExtra("gyroMax", cfg.gyroMax);
            cfg.cadMin = intent.getDoubleExtra("cadMin", cfg.cadMin);
            cfg.cadMax = intent.getDoubleExtra("cadMax", cfg.cadMax);
        }
        hwBase = -1; hwLast = -1;
        startedAtMs = System.currentTimeMillis();
        engine = new StepEngine(cfg);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "antrenman:steps");
            wl.acquire(4L * 60L * 60L * 1000L);
        }

        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor det = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        Sensor cnt = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gyr = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        hasDetector = det != null; hasCounter = cnt != null; hasAccel = acc != null; hasGyro = gyr != null;
        if (det != null) sm.registerListener(this, det, SensorManager.SENSOR_DELAY_FASTEST);
        if (cnt != null) sm.registerListener(this, cnt, SensorManager.SENSOR_DELAY_FASTEST);
        if (acc != null) sm.registerListener(this, acc, 20000);   // ~50 Hz
        if (gyr != null) sm.registerListener(this, gyr, 20000);
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Yürüyüş kaydı", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Intent li = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0, li, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CH)
                .setContentTitle("Yürüyüş kaydediliyor")
                .setContentText("Adımlar doğrulanarak sayılıyor")
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        StepEngine en = engine;
        if (en == null) return;
        double tMs = e.timestamp / 1000000.0;
        switch (e.sensor.getType()) {
            case Sensor.TYPE_STEP_DETECTOR:
                en.onStep(tMs);
                break;
            case Sensor.TYPE_STEP_COUNTER:
                if (hwBase < 0) hwBase = e.values[0];
                hwLast = e.values[0];
                break;
            case Sensor.TYPE_ACCELEROMETER:
                en.onAccel(tMs, e.values[0], e.values[1], e.values[2]);
                break;
            case Sensor.TYPE_GYROSCOPE:
                en.onGyro(tMs, e.values[0], e.values[1], e.values[2]);
                break;
            default:
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    public void onDestroy() {
        if (sm != null) sm.unregisterListener(this);
        if (wl != null && wl.isHeld()) wl.release();
        engine = null;
        instance = null;
        super.onDestroy();
    }
}

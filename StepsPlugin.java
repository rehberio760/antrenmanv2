package com.antrenman.cizelge;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
    name = "Steps",
    permissions = {
        @Permission(alias = "activity", strings = { Manifest.permission.ACTIVITY_RECOGNITION }),
        @Permission(alias = "camera", strings = { Manifest.permission.CAMERA }),
        @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" })
    }
)
public class StepsPlugin extends Plugin {

    private boolean granted(String perm) {
        return ContextCompat.checkSelfPermission(getContext(), perm) == PackageManager.PERMISSION_GRANTED;
    }

    private JSObject statusObj() {
        JSObject o = new JSObject();
        o.put("activity", Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACTIVITY_RECOGNITION));
        o.put("camera", granted(Manifest.permission.CAMERA));
        o.put("notifications", Build.VERSION.SDK_INT < 33 || granted("android.permission.POST_NOTIFICATIONS"));
        o.put("running", StepService.engine != null);
        return o;
    }

    @PluginMethod
    public void status(PluginCall call) {
        call.resolve(statusObj());
    }

    @PluginMethod
    public void requestAll(PluginCall call) {
        requestPermissionForAliases(new String[] { "activity", "camera", "notifications" }, call, "permsDone");
    }

    @PermissionCallback
    private void permsDone(PluginCall call) {
        call.resolve(statusObj());
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (StepService.engine != null) {
            JSObject r = new JSObject();
            r.put("ok", true);
            r.put("already", true);
            call.resolve(r);
            return;
        }
        if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            call.reject("Fiziksel aktivite izni gerekli");
            return;
        }
        Intent i = new Intent(getContext(), StepService.class);
        JSObject cfg = call.getObject("cfg");
        if (cfg != null) {
            i.putExtra("minBout", cfg.optInt("minBout", 10));
            i.putExtra("maxCv", cfg.optDouble("maxCv", 0.35));
            i.putExtra("rhoMin", cfg.optDouble("rhoMin", 0.25));
            i.putExtra("gyroMax", cfg.optDouble("gyroMax", 6.0));
            i.putExtra("cadMin", cfg.optDouble("cadMin", 1.0));
            i.putExtra("cadMax", cfg.optDouble("cadMax", 3.2));
        }
        try {
            ContextCompat.startForegroundService(getContext(), i);
        } catch (Exception e) {
            call.reject("Hizmet başlatılamadı: " + e.getMessage());
            return;
        }
        long until = System.currentTimeMillis() + 2500;
        while (StepService.engine == null && System.currentTimeMillis() < until) {
            try { Thread.sleep(50); } catch (InterruptedException ie) { break; }
        }
        JSObject r = new JSObject();
        r.put("ok", StepService.engine != null);
        r.put("detector", StepService.hasDetector);
        r.put("counter", StepService.hasCounter);
        r.put("accel", StepService.hasAccel);
        r.put("gyro", StepService.hasGyro);
        call.resolve(r);
    }

    private JSObject liveObj() {
        JSObject o = new JSObject();
        StepEngine en = StepService.engine;
        o.put("running", en != null);
        if (en == null) return o;
        StepEngine.Snap s = en.snapshot();
        o.put("elapsedMs", System.currentTimeMillis() - StepService.startedAtMs);
        o.put("raw", s.raw);
        o.put("validated", s.validated);
        o.put("walking", s.walking);
        o.put("hw", StepService.hwBase < 0 ? 0 : Math.round(StepService.hwLast - StepService.hwBase));
        o.put("cadence", s.cadence);
        o.put("cv", s.cv);
        o.put("rho", s.rho);
        o.put("rms", s.rms);
        o.put("gyro", s.gyro);
        o.put("failCadence", s.failCadence);
        o.put("failRhythm", s.failRhythm);
        o.put("failPeriodic", s.failPeriodic);
        o.put("failAmp", s.failAmp);
        o.put("failGyro", s.failGyro);
        o.put("okEvals", s.okEvals);
        o.put("avgCadence", s.avgCadence);
        o.put("avgCv", s.avgCv);
        o.put("avgRho", s.avgRho);
        o.put("detector", StepService.hasDetector);
        o.put("counter", StepService.hasCounter);
        o.put("accel", StepService.hasAccel);
        o.put("gyroAvail", StepService.hasGyro);
        return o;
    }

    @PluginMethod
    public void live(PluginCall call) {
        call.resolve(liveObj());
    }

    @PluginMethod
    public void stop(PluginCall call) {
        JSObject r = liveObj();
        getContext().stopService(new Intent(getContext(), StepService.class));
        call.resolve(r);
    }
}

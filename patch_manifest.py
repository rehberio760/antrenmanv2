#!/usr/bin/env python3
"""Capacitor'ın oluşturduğu AndroidManifest.xml dosyasına izinleri ve adım hizmetini ekler.
Birden fazla çalıştırılsa da aynı satırı iki kez eklemez."""
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "android/app/src/main/AndroidManifest.xml"
s = open(path, encoding="utf-8").read()

perms = [
    '<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />',
    '<uses-permission android:name="android.permission.CAMERA" />',
    '<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />',
    '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />',
    '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />',
    '<uses-permission android:name="android.permission.WAKE_LOCK" />',
    '<uses-feature android:name="android.hardware.camera" android:required="false" />',
    '<uses-feature android:name="android.hardware.sensor.stepdetector" android:required="false" />',
    '<uses-feature android:name="android.hardware.sensor.stepcounter" android:required="false" />',
    '<uses-feature android:name="android.hardware.sensor.accelerometer" android:required="false" />',
    '<uses-feature android:name="android.hardware.sensor.gyroscope" android:required="false" />',
]
add = []
for p in perms:
    key = p.split('android:name="')[1].split('"')[0]
    if key not in s:
        add.append("    " + p)

if add:
    i = s.index("<application")
    s = s[:i] + "\n".join(add) + "\n\n    " + s[i:]

service = '<service android:name=".StepService" android:exported="false" android:foregroundServiceType="health" />'
if ".StepService" not in s:
    j = s.rindex("</application>")
    s = s[:j] + "    " + service + "\n    " + s[j:]

open(path, "w", encoding="utf-8").write(s)
print("Manifest güncellendi:", path, "| eklenen satır:", len(add))

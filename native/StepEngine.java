package com.antrenman.cizelge;

import java.util.ArrayList;

/**
 * Adım doğrulama motoru. Saf Java, Android'e bağımlı değil.
 * Girdi: donanım adım dedektörü olayları + ivmeölçer + jiroskop örnekleri.
 * Çıktı: "yürüyüş paterni" doğrulanmış adım sayısı.
 */
public class StepEngine {

    public static class Config {
        public int minBout = 10;          // sayılmaya başlamak için gereken ardışık adım
        public int window = 12;           // değerlendirme penceresi (adım)
        public double maxGapSec = 2.0;    // bu süreden uzun boşluk seriyi sıfırlar
        public double cadMin = 1.0;       // adım/sn alt sınır
        public double cadMax = 3.2;       // adım/sn üst sınır
        public double maxCv = 0.35;       // adım aralığı değişkenliği üst sınırı
        public double rhoMin = 0.25;      // ivme periyodikliği alt sınırı
        public double rmsMin = 0.03;      // dikey ivme şiddeti alt sınırı (g)
        public double rmsMax = 3.0;       // üst sınır (g)
        public double gyroMax = 6.0;      // jiroskop şiddeti üst sınırı (rad/sn)
    }

    public static class Snap {
        public int raw, validated;
        public boolean walking;
        public double cadence = -1, cv = -1, rho = -1, rms = -1, gyro = -1;
        public int failCadence, failRhythm, failPeriodic, failAmp, failGyro;
        public int okEvals;
        public double avgCadence = -1, avgCv = -1, avgRho = -1;
    }

    private static final int OK = 0, SHORT = 1, CAD = 2, RHYTHM = 3, PERIODIC = 4, AMP = 5, GYRO = 6;
    private static final int CAP = 2048;

    private final Config cfg;
    private final ArrayList<Double> bout = new ArrayList<Double>();
    private double lastStepT = -1;
    private int stepSeq = 0, lastCommitSeq = 0;
    private boolean walking = false;
    private int raw = 0, validated = 0;

    private final double[] at = new double[CAP], av = new double[CAP];
    private int aHead = 0, aN = 0;
    private final double[] gt = new double[CAP], gmag = new double[CAP];
    private int gHead = 0, gN = 0;
    private double gx, gy, gz, lastAccT = -1;
    private boolean gInit = false;

    private double lastCad = -1, lastCv = -1, lastRho = -1, lastRms = -1, lastGyro = -1;
    private int failCad = 0, failRhythm = 0, failPeriodic = 0, failAmp = 0, failGyro = 0;
    private int okEvals = 0, rhoCnt = 0;
    private double sumCad = 0, sumCv = 0, sumRho = 0;

    public StepEngine(Config c) { this.cfg = c; }

    /** İvmeölçer örneği: t (ms), x/y/z (m/s^2). */
    public synchronized void onAccel(double tMs, double x, double y, double z) {
        final double G = 9.80665;
        x /= G; y /= G; z /= G;
        if (!gInit) { gx = x; gy = y; gz = z; gInit = true; lastAccT = tMs; }
        double dt = Math.max(1.0, tMs - lastAccT) / 1000.0;
        lastAccT = tMs;
        double a = Math.exp(-dt / 1.0);
        gx = a * gx + (1 - a) * x;
        gy = a * gy + (1 - a) * y;
        gz = a * gz + (1 - a) * z;
        double gm = Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (gm < 1e-6) gm = 1.0;
        double v = (x * gx + y * gy + z * gz) / gm - gm;   // dikey dinamik ivme (g)
        at[aHead] = tMs; av[aHead] = v;
        aHead = (aHead + 1) % CAP;
        if (aN < CAP) aN++;
    }

    /** Jiroskop örneği: t (ms), rad/sn. */
    public synchronized void onGyro(double tMs, double x, double y, double z) {
        gt[gHead] = tMs; gmag[gHead] = Math.sqrt(x * x + y * y + z * z);
        gHead = (gHead + 1) % CAP;
        if (gN < CAP) gN++;
    }

    /** Donanım adım dedektörü olayı: t (ms). */
    public synchronized void onStep(double tMs) {
        raw++;
        stepSeq++;
        if (lastStepT > 0 && tMs - lastStepT > cfg.maxGapSec * 1000.0) {
            bout.clear();
            walking = false;
        }
        lastStepT = tMs;
        bout.add(tMs);
        if (bout.size() > 400) {
            for (int i = 0; i < 200; i++) bout.remove(0);
        }
        if (!walking) {
            if (bout.size() >= cfg.minBout) {
                if (evaluate(false) == OK) {
                    walking = true;
                    int commit = Math.min(cfg.minBout, stepSeq - lastCommitSeq);
                    validated += commit;
                    lastCommitSeq = stepSeq;
                }
            }
        } else {
            if (evaluate(true) == OK) {
                validated++;
                lastCommitSeq = stepSeq;
            } else {
                walking = false;
            }
        }
    }

    private int evaluate(boolean relaxed) {
        int n = Math.min(cfg.window, bout.size());
        if (n < cfg.minBout) return SHORT;
        double[] ts = new double[n];
        for (int i = 0; i < n; i++) ts[i] = bout.get(bout.size() - n + i);
        double sum = 0;
        for (int i = 1; i < n; i++) sum += ts[i] - ts[i - 1];
        double mean = sum / (n - 1);
        double var = 0;
        for (int i = 1; i < n; i++) { double d = (ts[i] - ts[i - 1]) - mean; var += d * d; }
        double sd = Math.sqrt(var / (n - 1));
        double cv = sd / mean;
        double cad = 1000.0 / mean;
        lastCad = cad; lastCv = cv;
        if (cad < cfg.cadMin || cad > cfg.cadMax) { failCad++; return CAD; }
        if (cv > cfg.maxCv * (relaxed ? 1.5 : 1.0)) { failRhythm++; return RHYTHM; }

        double t0 = ts[0] - 300.0, t1 = ts[n - 1];
        double[] xs = new double[CAP];
        double[] xt = new double[CAP];
        int cnt = 0;
        for (int i = 0; i < aN; i++) {
            int idx = (aHead - aN + i + CAP) % CAP;
            if (at[idx] >= t0 && at[idx] <= t1) { xs[cnt] = av[idx]; xt[cnt] = at[idx]; cnt++; }
        }
        double rho = Double.NaN;
        if (cnt >= 20) {
            double m = 0;
            for (int i = 0; i < cnt; i++) m += xs[i];
            m /= cnt;
            double den = 0;
            for (int i = 0; i < cnt; i++) { xs[i] -= m; den += xs[i] * xs[i]; }
            double rms = Math.sqrt(den / cnt);
            lastRms = rms;
            if (rms < cfg.rmsMin || rms > cfg.rmsMax) { failAmp++; return AMP; }
            double hz = (cnt - 1) * 1000.0 / Math.max(1.0, xt[cnt - 1] - xt[0]);
            int l0 = (int) Math.round(0.8 * mean / 1000.0 * hz);
            int l1 = (int) Math.round(1.2 * mean / 1000.0 * hz);
            if (l0 < 1) l0 = 1;
            if (l1 > cnt - 3) l1 = cnt - 3;
            double best = -1;
            for (int lag = l0; lag <= l1; lag++) {
                double num = 0;
                for (int i = 0; i + lag < cnt; i++) num += xs[i] * xs[i + lag];
                double r = den > 1e-12 ? num / den : 0;
                if (r > best) best = r;
            }
            if (l1 >= l0) rho = best;
            lastRho = Double.isNaN(rho) ? -1 : rho;
            if (!Double.isNaN(rho) && rho < cfg.rhoMin * (relaxed ? 0.6 : 1.0)) { failPeriodic++; return PERIODIC; }
        }

        double gs = 0; int gc = 0;
        for (int i = 0; i < gN; i++) {
            int idx = (gHead - gN + i + CAP) % CAP;
            if (gt[idx] >= t0 && gt[idx] <= t1) { gs += gmag[idx] * gmag[idx]; gc++; }
        }
        if (gc >= 10) {
            double g = Math.sqrt(gs / gc);
            lastGyro = g;
            if (g > cfg.gyroMax) { failGyro++; return GYRO; }
        }

        okEvals++;
        sumCad += cad; sumCv += cv;
        if (!Double.isNaN(rho)) { sumRho += rho; rhoCnt++; }
        return OK;
    }

    public synchronized Snap snapshot() {
        Snap s = new Snap();
        s.raw = raw; s.validated = validated; s.walking = walking;
        s.cadence = lastCad; s.cv = lastCv; s.rho = lastRho; s.rms = lastRms; s.gyro = lastGyro;
        s.failCadence = failCad; s.failRhythm = failRhythm; s.failPeriodic = failPeriodic;
        s.failAmp = failAmp; s.failGyro = failGyro; s.okEvals = okEvals;
        if (okEvals > 0) { s.avgCadence = sumCad / okEvals; s.avgCv = sumCv / okEvals; }
        if (rhoCnt > 0) s.avgRho = sumRho / rhoCnt;
        return s;
    }
}

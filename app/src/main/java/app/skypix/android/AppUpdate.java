package app.skypix.android;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Over-the-air updates without any app store or Google service.
 * <ol>
 *   <li>{@link #check} reads a tiny {@code update.json} published next to the APK.</li>
 *   <li>{@link #download} fetches the APK and verifies its SHA-256.</li>
 *   <li>{@link #install} hands it to Android's PackageInstaller. Android itself checks that the new APK is signed
 *       with the same key as the installed app, so a swapped file cannot replace Purrcast.</li>
 * </ol>
 */
final class AppUpdate {
    private AppUpdate() {}

    /** Where releases are published (a public repo; only this host is contacted). */
    static final String BASE = "https://raw.githubusercontent.com/VK-VfX/Sam-s-Signal-Stabilizer/claude/signal-stabilizer-game-8le0y5/dist/";

    static final class Info {
        long code;
        String name = "";
        String url = "";
        String sha256 = "";
        String notes = "";
    }

    interface Progress {
        void onProgress(String text);
    }

    /** Latest result of {@link #check}, or null when up to date / unknown. */
    static volatile Info available;
    /** Human-readable state for the settings row. */
    static volatile String status = "";
    static volatile boolean busy;

    static long installedCode(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String installedName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Network call: returns the newer release, or null when this build is current. Never call on the main thread. */
    static Info check(Context c) throws Exception {
        String json = Net.get(BASE + "update.json");
        JSONObject o = new JSONObject(json);
        Info i = new Info();
        i.code = o.getLong("versionCode");
        i.name = o.optString("versionName", String.valueOf(i.code));
        i.url = o.optString("apkUrl", BASE + "skypix.apk");
        i.sha256 = o.optString("sha256", "");
        i.notes = o.optString("notes", "");
        Store.setLastUpdateCheck(c, System.currentTimeMillis());
        if (i.code > installedCode(c)) {
            available = i;
            return i;
        }
        available = null;
        return null;
    }

    /** Background check at most once a day; posts a notification once per new version. */
    static void checkQuietly(Context c) {
        try {
            if (!Store.autoUpdate(c)) return;
            if (System.currentTimeMillis() - Store.lastUpdateCheck(c) < 20L * 3600 * 1000) return;
            Info i = check(c);
            if (i != null && Store.notifiedVersion(c) != i.code) {
                Store.setNotifiedVersion(c, i.code);
                Notifier.updateAvailable(c, i.name, i.notes);
            }
        } catch (Throwable ignored) {
            // offline or the release file isn't there yet; try again next time
        }
    }

    /** Downloads and verifies the APK into the app's private cache. Never call on the main thread. */
    static File download(Context c, Info info, Progress p) throws Exception {
        File dir = new File(c.getCacheDir(), "updates");
        dir.mkdirs();
        File out = new File(dir, "skypix-" + info.code + ".apk");
        HttpURLConnection conn = (HttpURLConnection) new URL(info.url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", Net.UA);
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
            long total = conn.getContentLengthLong();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = conn.getInputStream();
            OutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[16384];
                long done = 0;
                int n, lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    md.update(buf, 0, n);
                    done += n;
                    if (done > 60L * 1024 * 1024) throw new IOException("file too large");
                    if (total > 0 && p != null) {
                        int pct = (int) (done * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            p.onProgress("Downloading " + pct + "%");
                        }
                    }
                }
            } finally {
                os.close();
                in.close();
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            if (info.sha256.length() > 0 && !hex.toString().equalsIgnoreCase(info.sha256)) {
                out.delete();
                throw new IOException("The download didn't match its checksum (the release may still be publishing). Try again in a few minutes.");
            }
            return out;
        } finally {
            conn.disconnect();
        }
    }

    /** True when Android will let Purrcast install packages; otherwise opens the one-time permission screen. */
    static boolean ensureInstallPermission(Context c) {
        if (Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls()) return true;
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
        return false;
    }

    /** Streams the APK into a PackageInstaller session; Android then asks the user to confirm. */
    static void install(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            OutputStream os = s.openWrite("skypix.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                s.fsync(os);
            } finally {
                in.close();
                os.close();
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            Intent cb = new Intent(c, InstallReceiver.class).setPackage(c.getPackageName());
            s.commit(PendingIntent.getBroadcast(c, id, cb, flags).getIntentSender());
        } catch (Exception e) {
            s.abandon();
            throw e;
        } finally {
            s.close();
        }
    }

    /** Receives the installer's progress; shows the system confirmation when Android asks for it. */
    public static final class InstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(confirm);
                }
                status = "Waiting for you to confirm the install";
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                status = "Updated";
                available = null;
            } else {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                status = "Install didn't finish" + (msg != null ? ": " + msg : "");
                Toast.makeText(c, status, Toast.LENGTH_LONG).show();
            }
            busy = false;
        }
    }
}

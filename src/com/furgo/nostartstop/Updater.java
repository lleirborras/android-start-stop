package com.furgo.nostartstop;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Finds the latest GitHub release and hands its APK to Android's installer.
 * Both methods block on the network; call them off the main thread.
 */
final class Updater {
    static final String ACTION_INSTALL_STATUS = "com.furgo.nostartstop.INSTALL_STATUS";

    private static final String REPO = "lleirborras/android-start-stop";
    private static final String LATEST_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String DOWNLOAD_PREFIX = "https://github.com/" + REPO + "/releases/download/";
    private static final int MAX_APK_BYTES = 20 * 1024 * 1024;

    static final class Release {
        final String tag;
        final String apkUrl;

        Release(String tag, String apkUrl) {
            this.tag = tag;
            this.apkUrl = apkUrl;
        }
    }

    static Release latest() throws IOException, JSONException {
        HttpURLConnection c = open(LATEST_URL);
        try {
            c.setRequestProperty("Accept", "application/vnd.github+json");
            if (c.getResponseCode() != 200) throw new IOException("GitHub answered " + c.getResponseCode());
            JSONObject release = new JSONObject(new String(read(c.getInputStream(), 1024 * 1024), "UTF-8"));
            String tag = release.getString("tag_name");
            JSONArray assets = release.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                String url = assets.getJSONObject(i).getString("browser_download_url");
                if (url.startsWith(DOWNLOAD_PREFIX) && url.endsWith(".apk")) return new Release(tag, url);
            }
            throw new IOException("release " + tag + " has no APK");
        } finally {
            c.disconnect();
        }
    }

    /**
     * Downloads the APK into an install session and commits it. Android then reports back to
     * MainActivity (ACTION_INSTALL_STATUS), first to ask the user to confirm. Android itself
     * rejects an APK that is not signed with the same key as the installed app.
     */
    static void install(Context context, Release release) throws IOException {
        if (!release.apkUrl.startsWith(DOWNLOAD_PREFIX)) throw new IOException("unexpected download URL");
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        PackageInstaller.Session session = installer.openSession(installer.createSession(params));
        try {
            HttpURLConnection c = open(release.apkUrl);
            try {
                if (c.getResponseCode() != 200) throw new IOException("download answered " + c.getResponseCode());
                byte[] apk = read(c.getInputStream(), MAX_APK_BYTES);
                OutputStream out = session.openWrite("update.apk", 0, apk.length);
                try {
                    out.write(apk);
                    session.fsync(out);
                } finally {
                    out.close();
                }
            } finally {
                c.disconnect();
            }
            Intent status = new Intent(context, MainActivity.class).setAction(ACTION_INSTALL_STATUS);
            // The installer fills in the result extras, so from Android 12 the intent must be mutable.
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getActivity(context, 0, status, flags).getIntentSender());
        } catch (IOException e) {
            session.abandon();
            throw e;
        } catch (RuntimeException e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "nostartstop-updater");
        return c;
    }

    private static byte[] read(InputStream in, int maxBytes) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > maxBytes) throw new IOException("response larger than " + maxBytes + " bytes");
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}

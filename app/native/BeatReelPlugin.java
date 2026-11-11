package io.github.beatreelapp.twa;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * BeatReel native features:
 *  - save a finished video to the gallery (Movies/BeatReel)
 *  - receive a song or video shared from another app ("Share" -> BeatReel)
 *  - a notification when a video is saved while the app is in the background
 */
@CapacitorPlugin(
    name = "BeatReel",
    permissions = {
        @Permission(alias = "storage", strings = { Manifest.permission.WRITE_EXTERNAL_STORAGE })
    }
)
public class BeatReelPlugin extends Plugin {
    private File tmp;
    private JSObject pendingShare;

    @Override
    public void load() {
        Intent it = getActivity().getIntent();
        if (it != null) takeShare(it);
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        takeShare(intent);
    }

    private void takeShare(Intent it) {
        try {
            if (!Intent.ACTION_SEND.equals(it.getAction())) return;
            Uri u = it.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u == null) return;
            ContentResolver cr = getContext().getContentResolver();
            String mime = it.getType();
            String crMime = cr.getType(u);
            if (crMime != null) mime = crMime;
            String name = "shared";
            try (Cursor c = cr.query(u, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String n = c.getString(0);
                    if (n != null && n.length() > 0) name = n;
                }
            } catch (Exception ignored) {}
            File dir = new File(getContext().getCacheDir(), "shared");
            if (dir.exists()) {
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
            }
            dir.mkdirs();
            String safe = name.replaceAll("[^A-Za-z0-9._ -]", "_");
            File out = new File(dir, System.currentTimeMillis() + "_" + safe);
            try (InputStream in = cr.openInputStream(u); OutputStream os = new FileOutputStream(out)) {
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) os.write(b, 0, n);
            }
            JSObject d = new JSObject();
            d.put("path", out.getAbsolutePath());
            d.put("name", name);
            d.put("mime", mime == null ? "" : mime);
            pendingShare = d;
            notifyListeners("shared", d, true);
            it.setAction(Intent.ACTION_MAIN); // do not open the same file again after a restart
        } catch (Exception e) {
            JSObject d = new JSObject();
            d.put("error", String.valueOf(e));
            notifyListeners("shared", d, true);
        }
    }

    @PluginMethod
    public void getShared(PluginCall call) {
        JSObject r = new JSObject();
        if (pendingShare != null) r.put("file", pendingShare);
        pendingShare = null;
        call.resolve(r);
    }

    // ---- saving a video, sent from the page in base64 parts ----

    @PluginMethod
    public void fileStart(PluginCall call) {
        try {
            File dir = new File(getContext().getCacheDir(), "out");
            dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            tmp = new File(dir, "video.tmp");
            new FileOutputStream(tmp).close();
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e));
        }
    }

    @PluginMethod
    public void fileAppend(PluginCall call) {
        try {
            if (tmp == null) { call.reject("no file"); return; }
            byte[] b = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
            try (FileOutputStream os = new FileOutputStream(tmp, true)) { os.write(b); }
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e));
        }
    }

    @PluginMethod
    public void fileSave(PluginCall call) {
        if (Build.VERSION.SDK_INT < 29 && getPermissionState("storage") != PermissionState.GRANTED) {
            requestPermissionForAlias("storage", call, "storageBack");
            return;
        }
        doSave(call);
    }

    @PermissionCallback
    private void storageBack(PluginCall call) {
        if (getPermissionState("storage") == PermissionState.GRANTED) doSave(call);
        else call.reject("denied");
    }

    private void doSave(PluginCall call) {
        try {
            if (tmp == null || !tmp.exists()) { call.reject("no file"); return; }
            String name = call.getString("name", "beatreel_video.mp4");
            String mime = call.getString("mime", "video/mp4");
            Context ctx = getContext();
            String where;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Video.Media.MIME_TYPE, mime);
                v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/BeatReel");
                v.put(MediaStore.Video.Media.IS_PENDING, 1);
                ContentResolver cr = ctx.getContentResolver();
                Uri u = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                if (u == null) { call.reject("insert failed"); return; }
                try (InputStream in = new FileInputStream(tmp); OutputStream os = cr.openOutputStream(u)) {
                    copy(in, os);
                }
                v.clear();
                v.put(MediaStore.Video.Media.IS_PENDING, 0);
                cr.update(u, v, null, null);
                where = u.toString();
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "BeatReel");
                dir.mkdirs();
                File out = new File(dir, name);
                try (InputStream in = new FileInputStream(tmp); OutputStream os = new FileOutputStream(out)) {
                    copy(in, os);
                }
                MediaScannerConnection.scanFile(ctx, new String[] { out.getAbsolutePath() }, new String[] { mime }, null);
                where = out.getAbsolutePath();
            }
            tmp.delete();
            tmp = null;
            JSObject r = new JSObject();
            r.put("uri", where);
            call.resolve(r);
        } catch (Exception e) {
            call.reject(String.valueOf(e));
        }
    }

    private static void copy(InputStream in, OutputStream os) throws Exception {
        byte[] b = new byte[1 << 16];
        int n;
        while ((n = in.read(b)) > 0) os.write(b, 0, n);
    }

    // ---- keep the screen on while a video is being made ----

    @PluginMethod
    public void keepOn(PluginCall call) {
        final boolean on = Boolean.TRUE.equals(call.getBoolean("on", true));
        getActivity().runOnUiThread(() -> {
            try {
                if (on) getActivity().getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getActivity().getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Exception e) { }
        });
        call.resolve();
    }

    // ---- notification ----

    @PluginMethod
    public void notifyDone(PluginCall call) {
        try {
            Context ctx = getContext();
            if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(getActivity(), new String[] { Manifest.permission.POST_NOTIFICATIONS }, 7701);
                call.resolve();
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("done", "Video ready", NotificationManager.IMPORTANCE_DEFAULT);
                ctx.getSystemService(NotificationManager.class).createNotificationChannel(ch);
            }
            Intent open = new Intent(ctx, getActivity().getClass());
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            NotificationCompat.Builder nb = new NotificationCompat.Builder(ctx, "done")
                .setSmallIcon(ctx.getApplicationInfo().icon)
                .setContentTitle(call.getString("title", "BeatReel"))
                .setContentText(call.getString("body", "Your video is ready"))
                .setContentIntent(pi)
                .setAutoCancel(true);
            NotificationManagerCompat.from(ctx).notify(1, nb.build());
            call.resolve();
        } catch (Exception e) {
            call.resolve();
        }
    }
}

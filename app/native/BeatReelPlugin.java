package io.github.beatreelapp.twa;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
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

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BeatReel native features:
 *  - save a finished video to the gallery (Movies/BeatReel)
 *  - receive a song or video shared from another app ("Share" -> BeatReel)
 *  - a notification when a video is saved while the app is in the background
 *  - share the saved video straight to TikTok, Instagram, WhatsApp... (like CapCut)
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
                tmp.delete();
                tmp = null;
                // Android 9 and older: the gallery gives the file a content:// address, needed to share it to other apps
                final String path = out.getAbsolutePath();
                final AtomicBoolean done = new AtomicBoolean(false);
                MediaScannerConnection.scanFile(ctx, new String[] { path }, new String[] { mime }, (p, u) -> {
                    if (done.getAndSet(true)) return;
                    JSObject r = new JSObject();
                    r.put("uri", u != null ? u.toString() : path);
                    call.resolve(r);
                });
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (done.getAndSet(true)) return;
                    JSObject r = new JSObject();
                    r.put("uri", path);
                    call.resolve(r);
                }, 5000);
                return;
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

    // ---- share the saved video to another app ----

    static final String[][] SHARE_APPS = {
        { "tiktok", "TikTok", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" },
        { "instagram", "Instagram", "com.instagram.android" },
        { "whatsapp", "WhatsApp", "com.whatsapp", "com.whatsapp.w4b" },
        { "youtube", "YouTube", "com.google.android.youtube" },
        { "facebook", "Facebook", "com.facebook.katana", "com.facebook.lite" },
        { "snapchat", "Snapchat", "com.snapchat.android" },
    };

    /** Which of these apps are installed, with their own icon (drawn by the phone, not by us). */
    @PluginMethod
    public void shareApps(PluginCall call) {
        PackageManager pm = getContext().getPackageManager();
        JSArray list = new JSArray();
        for (String[] a : SHARE_APPS) {
            for (int k = 2; k < a.length; k++) {
                try {
                    pm.getApplicationInfo(a[k], 0);
                    JSObject o = new JSObject();
                    o.put("id", a[0]);
                    o.put("name", a[1]);
                    o.put("pkg", a[k]);
                    o.put("icon", iconOf(pm, a[k]));
                    list.put(o);
                    break;
                } catch (Exception ignored) { }
            }
        }
        JSObject r = new JSObject();
        r.put("apps", list);
        call.resolve(r);
    }

    private static String iconOf(PackageManager pm, String pkg) {
        try {
            Drawable d = pm.getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, 128, 128);
            d.draw(cv);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bo);
            return "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /** Opens the app (pkg) with the video ready to post, or the phone's share list when pkg is empty. */
    @PluginMethod
    public void share(PluginCall call) {
        String uri = call.getString("uri", "");
        String pkg = call.getString("pkg", "");
        if (uri == null || uri.isEmpty()) { call.reject("no video"); return; }
        try {
            Uri u = uri.startsWith("/") ? Uri.fromFile(new File(uri)) : Uri.parse(uri);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("video/mp4");
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.setClipData(ClipData.newRawUri("", u));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (pkg != null && !pkg.isEmpty()) {
                i.setPackage(pkg);
                try {
                    getActivity().startActivity(i);
                    call.resolve();
                    return;
                } catch (Exception notThere) {
                    i.setPackage(null);
                }
            }
            Intent ch = Intent.createChooser(i, "Share video");
            ch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getActivity().startActivity(ch);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e));
        }
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

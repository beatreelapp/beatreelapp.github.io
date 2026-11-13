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
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaScannerConnection;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BeatReel native features:
 *  - save a finished video to the gallery (Movies/BeatReel)
 *  - receive a song or video shared from another app ("Share" -> BeatReel)
 *  - a notification when a video is saved while the app is in the background
 *  - make the video with the phone's own H.264 chip (MediaCodec), like native editors do
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
            it.setAction(Intent.ACTION_MAIN); // do not open the same file again after a restart
            if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).hideHome();
            deliver(u, it.getType(), null);
        } catch (Exception e) {
            JSObject d = new JSObject();
            d.put("error", String.valueOf(e));
            notifyListeners("shared", d, true);
        }
    }

    /** A song or video for the editor (shared from another app, or picked on the start screen). Copied in the background. */
    public void deliver(final Uri u, final String mimeHint, final String want) {
        new Thread(() -> {
            try {
                ContentResolver cr = getContext().getContentResolver();
                String mime = mimeHint;
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
                if (want != null) d.put("want", want);
                pendingShare = d;
                notifyListeners("shared", d, true);
            } catch (Exception e) {
                JSObject d = new JSObject();
                d.put("error", String.valueOf(e));
                notifyListeners("shared", d, true);
            }
        }).start();
    }

    /** The editor page asks for the native start screen (its "Home" button), or hides it (tests). */
    @PluginMethod
    public void showHome(PluginCall call) {
        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).showHome();
        call.resolve();
    }

    @PluginMethod
    public void hideHome(PluginCall call) {
        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).hideHome();
        call.resolve();
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

    // ---- video encoder of the phone (MediaCodec, hardware when the phone has it) ----
    // The page sends each finished picture (JPEG); it is drawn with OpenGL on the encoder's input surface
    // with an exact time stamp, and the H.264 data goes back to the page, which writes the MP4 as before.
    private MediaCodec enc;
    private Surface encSurf;
    private android.opengl.EGLDisplay eDpy = EGL14.EGL_NO_DISPLAY;
    private android.opengl.EGLContext eCtx = EGL14.EGL_NO_CONTEXT;
    private android.opengl.EGLSurface eSurf = EGL14.EGL_NO_SURFACE;
    private int glProg, glTex, encW, encH, encFps, glAP, glAT;
    private boolean texInit, cfgSent;
    private Bitmap encBmp;
    private byte[] encCfg;
    private FloatBuffer quad;
    private final MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();

    @PluginMethod
    public void encStart(PluginCall call) {
        encRelease();
        try {
            encW = call.getInt("w", 720);
            encH = call.getInt("h", 1280);
            encFps = call.getInt("fps", 30);
            int kbps = call.getInt("kbps", 7000);
            MediaFormat f = MediaFormat.createVideoFormat("video/avc", encW, encH);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, encFps);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            enc = MediaCodec.createEncoderByType("video/avc");
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encSurf = enc.createInputSurface();
            enc.start();
            // OpenGL on the encoder surface
            eDpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            if (!EGL14.eglInitialize(eDpy, ver, 0, ver, 1)) throw new RuntimeException("egl init");
            int[] attr = { EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE };
            android.opengl.EGLConfig[] cfg = new android.opengl.EGLConfig[1];
            int[] n = new int[1];
            if (!EGL14.eglChooseConfig(eDpy, attr, 0, cfg, 0, 1, n, 0) || n[0] < 1) throw new RuntimeException("egl config");
            eCtx = EGL14.eglCreateContext(eDpy, cfg[0], EGL14.EGL_NO_CONTEXT, new int[] { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE }, 0);
            if (eCtx == null || eCtx == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("egl context");
            eSurf = EGL14.eglCreateWindowSurface(eDpy, cfg[0], encSurf, new int[] { EGL14.EGL_NONE }, 0);
            if (eSurf == null || eSurf == EGL14.EGL_NO_SURFACE) throw new RuntimeException("egl surface");
            if (!EGL14.eglMakeCurrent(eDpy, eSurf, eSurf, eCtx)) throw new RuntimeException("egl current");
            int vs = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 aP;attribute vec2 aT;varying vec2 vT;void main(){gl_Position=aP;vT=aT;}");
            int fs = shader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float;varying vec2 vT;uniform sampler2D uS;void main(){gl_FragColor=texture2D(uS,vT);}");
            glProg = GLES20.glCreateProgram();
            GLES20.glAttachShader(glProg, vs);
            GLES20.glAttachShader(glProg, fs);
            GLES20.glLinkProgram(glProg);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(glProg, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("gl link");
            glAP = GLES20.glGetAttribLocation(glProg, "aP");
            glAT = GLES20.glGetAttribLocation(glProg, "aT");
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            glTex = t[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glTex);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            // x, y, u, v : the first row of the picture is the top of the video
            float[] q = { -1, -1, 0, 1,  1, -1, 1, 1,  -1, 1, 0, 0,  1, 1, 1, 0 };
            quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(q).position(0);
            texInit = false;
            cfgSent = false;
            encCfg = null;
            JSObject r = new JSObject();
            r.put("name", enc.getName());
            call.resolve(r);
        } catch (Throwable e) {
            encRelease();
            call.reject("encStart " + e);
        }
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("gl shader " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    @PluginMethod
    public void encFrame(PluginCall call) {
        try {
            if (enc == null) { call.reject("no encoder"); return; }
            String d = call.getString("d", "");
            int i = call.getInt("i", 0);
            byte[] jpg = Base64.decode(d, Base64.DEFAULT);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inMutable = true;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = null;
            if (encBmp != null) {
                o.inBitmap = encBmp;
                try { b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o); } catch (IllegalArgumentException ex) { b = null; }
            }
            if (b == null) { o.inBitmap = null; b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o); }
            if (b == null) { call.reject("bad picture"); return; }
            encBmp = b;
            if (!EGL14.eglMakeCurrent(eDpy, eSurf, eSurf, eCtx)) throw new RuntimeException("egl current");
            GLES20.glViewport(0, 0, encW, encH);
            GLES20.glUseProgram(glProg);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glTex);
            if (!texInit) { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0); texInit = true; }
            else GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, b);
            quad.position(0);
            GLES20.glVertexAttribPointer(glAP, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(glAP);
            quad.position(2);
            GLES20.glVertexAttribPointer(glAT, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(glAT);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            int ge = GLES20.glGetError();
            if (ge != GLES20.GL_NO_ERROR) throw new RuntimeException("gl error " + ge);
            EGLExt.eglPresentationTimeANDROID(eDpy, eSurf, i * 1000000000L / encFps);
            if (!EGL14.eglSwapBuffers(eDpy, eSurf)) throw new RuntimeException("egl swap");
            call.resolve(encDrain(false));
        } catch (Throwable e) {
            call.reject("encFrame " + e);
        }
    }

    private JSObject encDrain(boolean end) {
        JSArray out = new JSArray();
        int idle = 0;
        while (true) {
            int ix = enc.dequeueOutputBuffer(encInfo, end ? 10000 : 0);
            if (ix == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end || ++idle > 500) break;
                continue;
            }
            if (ix == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try {
                    MediaFormat nf = enc.getOutputFormat();
                    ByteBuffer a = nf.getByteBuffer("csd-0"), b2 = nf.getByteBuffer("csd-1");
                    if (a != null) {
                        byte[] x = new byte[a.remaining() + (b2 != null ? b2.remaining() : 0)];
                        int n0 = a.remaining();
                        a.duplicate().get(x, 0, n0);
                        if (b2 != null) b2.duplicate().get(x, n0, b2.remaining());
                        encCfg = x;
                    }
                } catch (Exception ignored) { }
                continue;
            }
            if (ix < 0) continue;
            idle = 0;
            ByteBuffer bb = enc.getOutputBuffer(ix);
            if (bb != null && encInfo.size > 0) {
                byte[] a = new byte[encInfo.size];
                bb.position(encInfo.offset);
                bb.get(a, 0, encInfo.size);
                if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) { if (encCfg == null) encCfg = a; }
                else {
                    JSObject o = new JSObject();
                    o.put("d", Base64.encodeToString(a, Base64.NO_WRAP));
                    o.put("t", encInfo.presentationTimeUs);
                    o.put("k", (encInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                    out.put(o);
                }
            }
            boolean eos = (encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            enc.releaseOutputBuffer(ix, false);
            if (eos) break;
        }
        JSObject r = new JSObject();
        r.put("out", out);
        if (encCfg != null && !cfgSent) { r.put("cfg", Base64.encodeToString(encCfg, Base64.NO_WRAP)); cfgSent = true; }
        return r;
    }

    @PluginMethod
    public void encEnd(PluginCall call) {
        try {
            if (enc == null) { call.reject("no encoder"); return; }
            enc.signalEndOfInputStream();
            JSObject r = encDrain(true);
            encRelease();
            call.resolve(r);
        } catch (Throwable e) {
            encRelease();
            call.reject("encEnd " + e);
        }
    }

    @PluginMethod
    public void encAbort(PluginCall call) {
        encRelease();
        call.resolve();
    }

    private void encRelease() {
        try { if (enc != null) { try { enc.stop(); } catch (Exception ignored) { } enc.release(); } } catch (Exception ignored) { }
        enc = null;
        try {
            if (eDpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eDpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eSurf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eDpy, eSurf);
                if (eCtx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eDpy, eCtx);
            }
        } catch (Exception ignored) { }
        eSurf = EGL14.EGL_NO_SURFACE;
        eCtx = EGL14.EGL_NO_CONTEXT;
        eDpy = EGL14.EGL_NO_DISPLAY;
        try { if (encSurf != null) encSurf.release(); } catch (Exception ignored) { }
        encSurf = null;
        encBmp = null;
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

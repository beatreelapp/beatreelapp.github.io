package io.github.beatreelapp.twa;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.PluginHandle;

public class MainActivity extends BridgeActivity {
    static final String HOST = "beatreelapp.github.io";
    HomeView home;
    ActivityResultLauncher<Intent> picker;
    String want = "video";
    ActivityResultLauncher<Intent> pickerWeb;
    ActivityResultLauncher<String[]> askMedia;
    GalleryView gallery;
    // the open gallery request: kind (video/media/image/music), several or one, page input id (null = start screen)
    String gKind = "video", gTarget = null;
    boolean gMulti = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BeatReelPlugin.class);
        // opened with "Share -> BeatReel": go straight to the editor, no start screen
        final boolean sharedIn = getIntent() != null && Intent.ACTION_SEND.equals(getIntent().getAction());
        super.onCreate(savedInstanceState);
        // Test app only (debug build): lets the automatic test on the virtual phone drive the page. Never on in the real app.
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true);

        // file picker of the phone (native): the chosen song/video goes to the editor page
        picker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == Activity.RESULT_OK && r.getData() != null && r.getData().getData() != null) {
                Uri u = r.getData().getData();
                // keep the start screen until the editor is ready (the page hides it), so the empty editor never flashes
                hideLater(12000);
                BeatReelPlugin p = plugin();
                if (p != null) p.deliver(u, r.getData().getType(), want);
            }
        });

        // the page's own file buttons, when the phone's file picker is used (several files allowed)
        pickerWeb = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            List<Uri> us = new ArrayList<>();
            if (r.getResultCode() == Activity.RESULT_OK && r.getData() != null) {
                ClipData cd = r.getData().getClipData();
                if (cd != null) for (int k = 0; k < cd.getItemCount(); k++) { Uri u = cd.getItemAt(k).getUri(); if (u != null) us.add(u); }
                else if (r.getData().getData() != null) us.add(r.getData().getData());
            }
            BeatReelPlugin p = plugin();
            if (p != null) p.deliverMany(us, null, gTarget);
        });
        // "Allow access to photos and videos / music" (asked once; if refused, the phone's file picker is used)
        askMedia = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), res -> {
            boolean ok = false;
            for (Map.Entry<String, Boolean> e : res.entrySet()) if (Boolean.TRUE.equals(e.getValue())) ok = true;
            if (ok || hasMedia(gKind)) showGallery();
            else {
                prefs().edit().putBoolean("galno_" + permGroup(gKind), true).apply();
                systemPick();
            }
        });

        // native start screen on top of the editor page (the page loads behind it, so it is ready when needed)
        home = new HomeView(this, new HomeView.Host() {
            public void pick(String w) {
                want = w;
                openGallery("video".equals(w) ? "video" : "music", false, null);
            }
            public void web(String js) { hideLater(1500); evalJs(js + READY, null); }
            public void eval(String js, ValueCallback<String> cb) { evalJs(js, cb); }
            public void soon(String what) { Toast.makeText(MainActivity.this, what, Toast.LENGTH_SHORT).show(); }
            public void openVideo(Uri u) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(u, "video/*");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                } catch (Exception e) { Toast.makeText(MainActivity.this, "No video player found", Toast.LENGTH_SHORT).show(); }
            }
            public void shareVideo(Uri u) {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("video/mp4");
                    i.putExtra(Intent.EXTRA_STREAM, u);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Intent ch = Intent.createChooser(i, "Share video");
                    ch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(ch);
                } catch (Exception ignored) { }
            }
        });
        addContentView(home, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // keep the start screen out from under the status bar and the navigation bar (Android 15 draws edge to edge)
        ViewCompat.setOnApplyWindowInsetsListener(home, (v, ins) -> {
            Insets b = ins.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return ins;
        });
        if (sharedIn) home.setVisibility(android.view.View.GONE);
        home.refresh();
        home.postDelayed(home::refresh, 2500);
        home.postDelayed(home::refresh, 6000);

        // Phone Back button:
        // - on the Stripe pay / subscription page -> back to the app (payment cancelled)
        // - in the editor -> back to the start screen (the editor keeps everything, an export keeps running)
        // - on the start screen -> just hide the app (like Home), never kill it
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView wv = getBridge() != null ? getBridge().getWebView() : null;
                String url = wv != null ? wv.getUrl() : null;
                String host = null;
                try { if (url != null) host = Uri.parse(url).getHost(); } catch (Exception ignored) {}
                if (wv != null && host != null && !HOST.equals(host) && !"localhost".equals(host)) {
                    wv.loadUrl("https://" + HOST + "/?paid=cancel");
                    return;
                }
                if (gallery != null) { gallery.close(new ArrayList<>()); return; }
                if (home != null && home.getVisibility() != android.view.View.VISIBLE) { showHome(); return; }
                moveTaskToBack(true);
            }
        });
    }

    BeatReelPlugin plugin() {
        try {
            PluginHandle h = getBridge().getPlugin("BeatReel");
            return h != null ? (BeatReelPlugin) h.getInstance() : null;
        } catch (Exception e) { return null; }
    }

    void evalJs(String js, ValueCallback<String> cb) {
        try {
            WebView wv = getBridge().getWebView();
            wv.post(() -> wv.evaluateJavascript(js, cb));
        } catch (Exception ignored) { }
    }

    public void showHome() {
        runOnUiThread(() -> {
            if (home == null) return;
            evalJs("try{au.pause();document.getElementById('bgv').pause()}catch(e){}", null);
            waitHide = false;
            home.setVisibility(android.view.View.VISIBLE);
            home.refresh();
        });
    }

    public void hideHome() {
        runOnUiThread(() -> { waitHide = false; if (home != null) home.setVisibility(android.view.View.GONE); });
    }

    // the page hides the start screen itself after it has drawn the editor (two frames); this is only a safety net
    static final String READY = ";try{requestAnimationFrame(function(){requestAnimationFrame(function(){BRN.hideHome()})})}catch(e){}";
    boolean waitHide = false;

    void hideLater(long ms) {
        waitHide = true;
        if (home != null) home.postDelayed(() -> { if (waitHide) hideHome(); }, ms);
    }

    // ---------- BeatReel's own gallery ----------
    SharedPreferences prefs() { return getSharedPreferences("br", Context.MODE_PRIVATE); }
    static String permGroup(String kind) { return "music".equals(kind) ? "a" : "v"; }

    String[] mediaPerms(String kind) {
        if (Build.VERSION.SDK_INT >= 33) {
            if ("music".equals(kind)) return new String[] { Manifest.permission.READ_MEDIA_AUDIO };
            if (Build.VERSION.SDK_INT >= 34) return new String[] { Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" };
            return new String[] { Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES };
        }
        return new String[] { Manifest.permission.READ_EXTERNAL_STORAGE };
    }

    boolean hasMedia(String kind) {
        for (String p : mediaPerms(kind)) if (ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED) return true;
        return false;
    }

    /** Opens the in-app gallery (asks for access the first time). Refused access = the phone's file picker, as before. */
    public void openGallery(String kind, boolean multi, String target) {
        runOnUiThread(() -> {
            gKind = kind; gMulti = multi; gTarget = target;
            if (gallery != null) return;
            if (hasMedia(kind)) { showGallery(); return; }
            if (prefs().getBoolean("galno_" + permGroup(kind), false)) { systemPick(); return; }
            try { askMedia.launch(mediaPerms(kind)); } catch (Exception e) { systemPick(); }
        });
    }

    void showGallery() {
        if (gallery != null) return;
        final String target = gTarget;
        gallery = new GalleryView(this, gKind, gMulti, new GalleryView.Done() {
            public void picked(List<GalleryView.Item> items) {
                removeGallery();
                if (items.isEmpty()) return;
                BeatReelPlugin p = plugin();
                if (p == null) return;
                if (target == null) {
                    // from the start screen: one file, the editor opens behind the start screen
                    hideLater(12000);
                    p.deliver(items.get(0).uri, items.get(0).mime, want);
                } else {
                    List<Uri> us = new ArrayList<>();
                    List<String> ms = new ArrayList<>();
                    for (GalleryView.Item it : items) { us.add(it.uri); ms.add(it.mime); }
                    p.deliverMany(us, ms, target);
                }
            }
            public void other() { removeGallery(); systemPick(); }
        });
        addContentView(gallery, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ViewCompat.setOnApplyWindowInsetsListener(gallery, (v, ins) -> {
            Insets b = ins.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return ins;
        });
        ViewCompat.requestApplyInsets(gallery);
    }

    void removeGallery() {
        if (gallery == null) return;
        ViewGroup pv = (ViewGroup) gallery.getParent();
        if (pv != null) pv.removeView(gallery);
        gallery = null;
    }

    /** The phone's own file picker (if access was refused, or "Files" was tapped). */
    void systemPick() {
        String type = "music".equals(gKind) ? "audio/*" : "image".equals(gKind) ? "image/*" : "video".equals(gKind) ? "video/*" : "*/*";
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType(type);
        if ("media".equals(gKind)) i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "video/*", "image/*" });
        i.addCategory(Intent.CATEGORY_OPENABLE);
        if (gMulti && gTarget != null) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            if (gTarget == null) picker.launch(i); else pickerWeb.launch(i);
        } catch (Exception e) { Toast.makeText(this, "No file app found", Toast.LENGTH_LONG).show(); }
    }
}

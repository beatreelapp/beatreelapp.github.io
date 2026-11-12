package io.github.beatreelapp.twa;

import android.app.Activity;
import android.content.Intent;
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
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.PluginHandle;

public class MainActivity extends BridgeActivity {
    static final String HOST = "beatreelapp.github.io";
    HomeView home;
    ActivityResultLauncher<Intent> picker;
    String want = "video";

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
                hideHome();
                BeatReelPlugin p = plugin();
                if (p != null) p.deliver(u, r.getData().getType(), want);
            }
        });

        // native start screen on top of the editor page (the page loads behind it, so it is ready when needed)
        home = new HomeView(this, new HomeView.Host() {
            public void pick(String w) {
                want = w;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("video".equals(w) ? "video/*" : "audio/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);
                try { picker.launch(i); } catch (Exception e) { Toast.makeText(MainActivity.this, "No file app found", Toast.LENGTH_LONG).show(); }
            }
            public void web(String js) { hideHome(); evalJs(js, null); }
            public void eval(String js, ValueCallback<String> cb) { evalJs(js, cb); }
            public void soon(String what) { Toast.makeText(MainActivity.this, what + ": coming soon", Toast.LENGTH_SHORT).show(); }
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
            home.setVisibility(android.view.View.VISIBLE);
            home.refresh();
        });
    }

    public void hideHome() {
        runOnUiThread(() -> { if (home != null) home.setVisibility(android.view.View.GONE); });
    }
}

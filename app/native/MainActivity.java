package io.github.beatreelapp.twa;

import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    static final String HOST = "beatreelapp.github.io";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BeatReelPlugin.class);
        super.onCreate(savedInstanceState);

        // Phone Back button:
        // - on the Stripe pay / subscription page -> back to the app (payment cancelled)
        // - inside the app -> just hide the app (like Home), never kill it (an export may be running)
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
                moveTaskToBack(true);
            }
        });
    }
}

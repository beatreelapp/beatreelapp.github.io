package io.github.beatreelapp.twa;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BeatReelPlugin.class);
        super.onCreate(savedInstanceState);
    }
}

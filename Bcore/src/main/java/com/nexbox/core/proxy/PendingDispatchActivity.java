package com.nexbox.core.proxy;
import android.app.Activity;
import android.os.Bundle;
import com.nexbox.core.core.system.am.VirtualPendingIntents;
public final class PendingDispatchActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { VirtualPendingIntents.dispatch(getIntent()); } finally { finish(); }
    }
}

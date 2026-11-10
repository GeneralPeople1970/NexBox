package com.nexbox.core.fake.device;

import java.util.LinkedHashMap;
import java.util.Map;
import android.telephony.SubscriptionManager;
import com.nexbox.core.core.NativeCore;
import com.nexbox.core.utils.Slog;

/** Process-local property overlay. No real system property or modem configuration is written. */
final class SimPropertySpoofer {
    private SimPropertySpoofer() {}
    static void apply(SimProfile profile) {
        if (!profile.enabled) { NativeCore.setSimSpoof(null, null); return; }
        Map<String, String> properties = new LinkedHashMap<>();
        for (String field : new String[]{"gsm.sim.operator.numeric", "gsm.sim.operator.alpha", "gsm.sim.operator.iso-country",
                "gsm.operator.numeric", "gsm.operator.alpha", "gsm.operator.iso-country", "gsm.operator.isroaming", "gsm.sim.state"}) {
            StringBuilder values = new StringBuilder();
            int count = Math.max(1, profile.cards.size());
            for (int slot = 0; slot < count; slot++) {
                if (slot != 0) values.append(',');
                SimProfile.Card card = profile.cardForSlot(slot);
                String value = "";
                switch (field) {
                    case "gsm.sim.operator.numeric": value = card == null ? "" : card.home.numeric(); break;
                    case "gsm.sim.operator.alpha": value = card == null ? "" : card.home.name; break;
                    case "gsm.sim.operator.iso-country": value = card == null ? "" : card.home.countryIso; break;
                    case "gsm.operator.numeric": value = card == null ? "" : card.network.numeric(); break;
                    case "gsm.operator.alpha": value = card == null ? "" : card.network.name; break;
                    case "gsm.operator.iso-country": value = card == null ? "" : card.network.countryIso; break;
                    case "gsm.operator.isroaming": value = card != null && card.roaming ? "true" : "false"; break;
                    case "gsm.sim.state": value = card == null ? "ABSENT" : "LOADED"; break;
                }
                values.append(value);
            }
            properties.put(field, values.toString());
        }
        properties.put("persist.radio.multisim.config", profile.cards.size() > 1 ? "dsds" : "");
        NativeCore.setSimSpoof(properties.keySet().toArray(new String[0]), properties.values().toArray(new String[0]));
        try {
            // Clear this process's framework cache only; do not invalidate global system caches.
            java.lang.reflect.Method clear = SubscriptionManager.class.getDeclaredMethod("clearCaches");
            clear.setAccessible(true);
            clear.invoke(null);
        } catch (ReflectiveOperationException e) {
            Slog.w("SimProfile", "Subscription cache clearing is unavailable on this framework");
        }
    }
}

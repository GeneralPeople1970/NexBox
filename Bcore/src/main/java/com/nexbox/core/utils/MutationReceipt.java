package com.nexbox.core.utils;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.regex.Pattern;

/** Facts survive business failures. A request id correlates a call; it never authorizes replay. */
public final class MutationReceipt {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}");
    public final Bundle data = new Bundle();
    private final ArrayList<Bundle> effects = new ArrayList<>();

    public MutationReceipt(String requestId) {
        requireRequestId(requestId);
        data.putString("requestId", requestId);
        data.putString("mutationState", "none");
        data.putString("stage", "preflight");
        data.putBoolean("success", false);
        data.putParcelableArrayList("effects", effects);
    }

    public static void requireRequestId(String value) {
        if (value == null || !REQUEST_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("requestId must contain 1..96 ASCII letters, digits, dot, colon, dash or underscore");
        }
    }

    public void stage(String stage) { data.putString("stage", stage); }

    /** Call immediately before the first possible side effect of each step. */
    public void begin(String stage) {
        stage(stage);
        data.putString("mutationState", "unknown");
    }

    public void confirmed(String kind) { confirmed(kind, null); }

    public void confirmed(String kind, Bundle detail) {
        Bundle effect = detail == null ? new Bundle() : new Bundle(detail);
        effect.putString("kind", kind);
        effects.add(effect);
        data.putString("mutationState", "partial");
    }

    public Bundle success() {
        data.putBoolean("success", true);
        if (!"none".equals(data.getString("mutationState"))) data.putString("mutationState", "committed");
        stage("complete");
        return data;
    }

    public Bundle failure(String code) {
        data.putBoolean("success", false);
        data.putString("causeCode", code);
        String state = data.getString("mutationState");
        if ("committed".equals(state)) {
            state = "partial";
            data.putString("mutationState", state);
        }
        data.putString("errorCode", "unknown".equals(state) ? "operation_unconfirmed"
                : "partial".equals(state) ? "operation_partial" : code);
        return data;
    }

    public static final class Rejected extends IllegalStateException {
        public final String code;
        public Rejected(String code) { super(code); this.code = code; }
    }
}

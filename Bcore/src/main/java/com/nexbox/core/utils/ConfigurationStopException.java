package com.nexbox.core.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Configuration is already committed; retry only the remaining process stops, never the save. */
public final class ConfigurationStopException extends IllegalStateException {
    public final List<ConfigurationAppStopper.Target> failedTargets;

    public ConfigurationStopException(List<ConfigurationAppStopper.Target> failedTargets) {
        super("Configuration was saved, but " + failedTargets.size() + " app stops could not be confirmed");
        this.failedTargets = Collections.unmodifiableList(new ArrayList<>(failedTargets));
    }

    public void retry() {
        ConfigurationAppStopper.stop(failedTargets);
    }
}

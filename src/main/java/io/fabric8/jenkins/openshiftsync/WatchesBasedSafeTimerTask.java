package io.fabric8.jenkins.openshiftsync;

import java.util.HashMap;
import java.util.Map;

import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.client.Watch;

public abstract class WatchesBasedSafeTimerTask extends SafeTimerTask {

    protected final Map<String, Watch> watches = new HashMap<>();

    public final Map<String, Watch> getWatches() {
        return watches;
    }

    public void closeWatches() {
        for (Watch watch : getWatches().values()) {
            watch.close();
        }
        getWatches().clear();
    }
}

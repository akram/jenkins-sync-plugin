/**
 * Copyright (C) 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync;

import static java.net.HttpURLConnection.HTTP_GONE;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import jenkins.util.Timer;

public abstract class BaseWatcher {
    private final Logger LOGGER = Logger.getLogger(BaseWatcher.class.getName());

    protected ScheduledFuture<?> relistingPromise;
    protected final String[] namespaces;
    protected WatchesBasedSafeTimerTask safeTimerTask;

//    private List<SafeTimerTask> timerTasks;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public BaseWatcher(String[] namespaces) {
        Class<? extends BaseWatcher> clazz = this.getClass();
        LOGGER.info("Creating watcher for: " + clazz + " using constructor");
        this.namespaces = namespaces;
        this.safeTimerTask = getStartTimerTask();
//        this.timerTasks = new ArrayList<>();
    }

    /**
     * @return
     */
    public abstract WatchesBasedSafeTimerTask getStartTimerTask();

    public abstract int getListIntervalInSeconds();

    public abstract <T> void eventReceived(io.fabric8.kubernetes.client.Watcher.Action action, T resource);

    public Map<String, Watch> getWatches() {
        return getStartTimerTask().getWatches();
    }

    public synchronized void start() {
        // lets do this in a background thread to avoid errors like:
        // Tried proxying io.fabric8.jenkins.openshiftsync.GlobalPluginConfiguration to
        // support a circular dependency, but it is not an interface.

        // Do the first run after 1 second and then every second.
//        SafeTimerTask timerTask = (SafeTimerTask) getStartTimerTask();
//        this.timerTasks.add(timerTask);
        this.safeTimerTask = getStartTimerTask();
        this.relistingPromise = Timer.get().scheduleAtFixedRate(safeTimerTask, 1, getListIntervalInSeconds(), SECONDS);
    }

    public void stop() {
        Class<? extends BaseWatcher> clazz = this.getClass();
        LOGGER.info("Watcher for: " + clazz + " received stop: Stopping !!");
        LOGGER.info("TimerTasks for " + clazz + "cancelled and  cleared !!");
        Map<String, Watch> watches = Collections.synchronizedMap(getWatches());
        watches.clear();
        LOGGER.info("Relisting promises for " + clazz + "cancelled and  cleared !!");

        LOGGER.info("Watchers for " + clazz + "cancelled and  cleared !!");
        if (this.safeTimerTask != null) {
            this.safeTimerTask.closeWatches();
            this.safeTimerTask.cancel();
            LOGGER.info("Re-sync Timer for " + clazz + "cancelled and all related watches cleared !!");
            this.safeTimerTask = null;
        }
        if (this.relistingPromise != null) {
            this.relistingPromise.cancel(true);
            LOGGER.info("Re-sync Promise for " + clazz + "cancelled and all related watches cleared !!");
            this.relistingPromise = null;
        }
    }

    /**
     * @param e
     * @param namespace
     */
    public void onClose(KubernetesClientException e, String namespace) {
        // scans of fabric client confirm this call be called with null
        // we do not want to totally ignore this, as the closing of the
        // watch can effect responsiveness
        String clazz = this.getClass().getName();
        LOGGER.info("Watch for type " + clazz + " closed for namespace: " + namespace);
        if (e != null) {
            LOGGER.warning(e.toString());
            e.printStackTrace();
            if (e.getStatus() != null && e.getStatus().getCode() == HTTP_GONE) {
                stop();
                start();
            }
        }
        // clearing the watches here will signal the extending classes
        // to attempt to re-establish the watch the next time they attempt
        // to list; should shield from rapid/repeated close/reopen cycles
        // doing it in this fashion
        Map<String, Watch> watches = Collections.synchronizedMap(getWatches());
        watches.remove(namespace);
    }

    public void addWatch(String namespace, Watch watch) {
        Map<String, Watch> watches = Collections.synchronizedMap(getWatches());
        synchronized (watches) {
            Watch oldWatch = watches.putIfAbsent(namespace, watch);
            String clazz = this.getClass().getName();
            if (oldWatch != null) {
                LOGGER.info("Watch for type " + clazz + " closed for namespace: " + namespace + ", watch: " + oldWatch);
                oldWatch.close();
            }
        }
    }

}

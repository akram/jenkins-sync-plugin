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

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.CONFIG_MAP;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.configMapContainsSlave;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.podTemplatesFromConfigMap;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.processSlavesForAddEvent;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.processSlavesForDeleteEvent;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.processSlavesForModifyEvent;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.trackedPodTemplates;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.internal.WatchConnectionManager;
import io.fabric8.openshift.client.OpenShiftClient;

public class ConfigMapWatcher extends BaseWatcher {
    private static ConfigMapWatcherTimerTask TIMER_TASK;

    private final class ConfigMapWatcherTimerTask extends WatchesBasedSafeTimerTask {

        @Override
        public void doRun() {
            OpenShiftUtils.initializeOpenShiftClient(GlobalPluginConfiguration.get().getServer());
            if (!CredentialsUtils.hasCredentials()) {
                LOGGER.fine("No Openshift Token credential defined.");
                return;
            }
            OpenShiftClient client = getAuthenticatedOpenShiftClient();
            LOGGER.info("Using openshift client: " + client);
            for (String ns : namespaces) {
                ConfigMapList configMaps = null;
                try {
                    LOGGER.fine("listing ConfigMap resources");
                    configMaps = client.configMaps().inNamespace(ns).list();
                    onInitialConfigMaps(configMaps);
                    LOGGER.fine("handled ConfigMap resources");
                } catch (Exception e) {
                    LOGGER.log(SEVERE, "Failed to load ConfigMaps: " + e, e);
                }
                try {
                    String rv = "0";
                    if (configMaps == null) {
                        LOGGER.warning("Unable to get config map list; impacts resource version used for watch");
                    } else {
                        rv = configMaps.getMetadata().getResourceVersion();
                    }
                    Map<String, Watch> watches = Collections.synchronizedMap(getWatches());
                    Watch watch = watches.get(ns);
                    if (watch == null) {
                        LOGGER.info("creating ConfigMap watch for namespace " + ns + " and resource version " + rv);
                        ConfigMapWatcher w = ConfigMapWatcher.this;
                        WatcherCallback<ConfigMap> watcher = new WatcherCallback<ConfigMap>(w, ns);
                        addWatch(ns, client.configMaps().inNamespace(ns).withResourceVersion(rv).watch(watcher));
                    } else {
                        LOGGER.info("Already existent configMap watch for ns: " + ns + ": watch" + watch);
                        WatchConnectionManager w = (WatchConnectionManager) watch;

                    }
                } catch (Exception e) {
                    LOGGER.log(SEVERE, "Failed to load ConfigMaps: " + e, e);
                }
            }
        }
    }

    private final Logger LOGGER = Logger.getLogger(getClass().getName());
    private final static Object lock = new Object();

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ConfigMapWatcher(String[] namespaces) {
        super(namespaces);
    }

    @Override
    public int getListIntervalInSeconds() {
        return GlobalPluginConfiguration.get().getConfigMapListInterval();
    }

    public WatchesBasedSafeTimerTask getStartTimerTask() {
        if (ConfigMapWatcher.TIMER_TASK == null) {
            synchronized (lock) {
                ConfigMapWatcher.TIMER_TASK = new ConfigMapWatcherTimerTask();
            }
        }
        String clazz = getClass().getName();
        LOGGER.info("Timertask for " + clazz + "is " + TIMER_TASK);
        return ConfigMapWatcher.TIMER_TASK;
    }

    public void start() {
        super.start();
        // lets process the initial state
        LOGGER.info("Now handling startup config maps!!");
    }

    public void eventReceived(Action action, ConfigMap configMap) {
        try {
            List<PodTemplate> agents = podTemplatesFromConfigMap(this, configMap);
            boolean hasSlaves = agents.size() > 0;
            String uid = configMap.getMetadata().getUid();
            String name = configMap.getMetadata().getName();
            String cmname = name;
            String namespace = configMap.getMetadata().getNamespace();
            switch (action) {
            case ADDED:
                if (hasSlaves) {
                    processSlavesForAddEvent(this, agents, CONFIG_MAP, uid, cmname, namespace);
                }
                break;
            case MODIFIED:
                processSlavesForModifyEvent(this, agents, CONFIG_MAP, uid, cmname, namespace);
                break;
            case DELETED:
                processSlavesForDeleteEvent(this, agents, CONFIG_MAP, uid, cmname, namespace);
                break;
            case ERROR:
                LOGGER.warning("watch for configMap " + name + " received error event ");
                break;
            default:
                LOGGER.warning("watch for configMap " + name + " received unknown event " + action);
                break;
            }
        } catch (Exception e) {
            LOGGER.log(WARNING, "Caught: " + e, e);
        }
    }

    @Override
    public <T> void eventReceived(io.fabric8.kubernetes.client.Watcher.Action action, T resource) {
        ConfigMap cfgmap = (ConfigMap) resource;
        eventReceived(action, cfgmap);
    }

    private void onInitialConfigMaps(ConfigMapList configMaps) {
        if (configMaps == null)
            return;
        if (trackedPodTemplates == null) {
            trackedPodTemplates = new ConcurrentHashMap<>(configMaps.getItems().size());
        }
        List<ConfigMap> items = configMaps.getItems();
        if (items != null) {
            for (ConfigMap configMap : items) {
                try {
                    String uid = configMap.getMetadata().getUid();
                    if (configMapContainsSlave(configMap) && !trackedPodTemplates.containsKey(uid)) {
                        List<PodTemplate> templates = podTemplatesFromConfigMap(this, configMap);
                        trackedPodTemplates.put(uid, templates);
                        for (PodTemplate podTemplate : templates) {
                            PodTemplateUtils.addPodTemplate(podTemplate);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.log(SEVERE, "Failed to update ConfigMap PodTemplates", e);
                }
            }
        }
    }
}

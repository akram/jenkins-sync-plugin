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

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenshiftClient;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.ImageStreamList;

public class ImageStreamWatcher extends BaseWatcher {

    private final static ConcurrentHashMap<String, Watch> watches = new ConcurrentHashMap<>();

    public ConcurrentHashMap<String, Watch> getWatches() {
        return watches;
    }

    private final static  Logger LOGGER = Logger.getLogger(ImageStreamWatcher.class.getName());

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ImageStreamWatcher(String[] namespaces) {
        super(namespaces);
    }

    @Override
    public int getListIntervalInSeconds() {
        return GlobalPluginConfiguration.get().getImageStreamListInterval();
    }

    public Runnable getStartTimerTask() {
        return new SafeTimerTask() {
            @Override
            public void doRun() {
                if (!CredentialsUtils.hasCredentials()) {
                    LOGGER.fine("No Openshift Token credential defined.");
                    return;
                }
                for (String ns : namespaces) {
                    ImageStreamList imageStreams = null;
                    try {
                        LOGGER.fine("listing ImageStream resources");
                        imageStreams = OpenShiftUtils.getOpenshiftClient().imageStreams().inNamespace(ns).list();
                        onImageStreamInitialization(imageStreams);
                        LOGGER.fine("handled ImageStream resources");
                    } catch (Exception e) {
                        LOGGER.log(SEVERE, "Failed to load ImageStreams: " + e, e);
                    }
                    try {
                        String resourceVersion = "0";
                        if (imageStreams == null) {
                            LOGGER.warning("Unable to get image stream list; impacts resource version used for watch");
                        } else {
                            resourceVersion = imageStreams.getMetadata().getResourceVersion();
                        }
                        if (watches.get(ns) == null) {
                            synchronized (watches) {
                                if (watches.get(ns) == null) {
                                    LOGGER.info("creating ImageStream watch for namespace " + ns
                                            + " and resource version " + resourceVersion);
                                    ImageStreamWatcher w = ImageStreamWatcher.this;
                                    WatcherCallback<ImageStream> watcher = new WatcherCallback<ImageStream>(w, ns);
                                    Watch watch = getOpenshiftClient().imageStreams().inNamespace(ns)
                                            .withResourceVersion(resourceVersion).watch(watcher);
                                    addWatch(ns, watch);
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.log(SEVERE, "Failed to load ImageStreams: " + e, e);
                    }
                }
            }
        };
    }

    public void start() {
        // lets process the initial state
        LOGGER.info("Now handling startup image streams!!");
        LOGGER.info("Watched namespaces: " + Arrays.toString(namespaces));
        super.start();
    }

    public void eventReceived(Action action, ImageStream imageStream) {
        try {
            List<PodTemplate> slaves = PodTemplateUtils.getPodTemplatesListFromImageStreams(imageStream);
            ObjectMeta metadata = imageStream.getMetadata();
            String uid = metadata.getUid();
            String name = metadata.getName();
            String namespace = metadata.getNamespace();
            switch (action) {
            case ADDED:
                processSlavesForAddEvent(slaves, PodTemplateUtils.IMAGESTREAM_TYPE, uid, name, namespace);
                break;
            case MODIFIED:
                processSlavesForModifyEvent(slaves, PodTemplateUtils.IMAGESTREAM_TYPE, uid, name, namespace);
                break;
            case DELETED:
                processSlavesForDeleteEvent(slaves, PodTemplateUtils.IMAGESTREAM_TYPE, uid, name, namespace);
                break;
            case ERROR:
                LOGGER.warning("watch for imageStream " + name + " received error event ");
                break;
            default:
                LOGGER.warning("watch for imageStream " + name + " received unknown event " + action);
                break;
            }
        } catch (Exception e) {
            LOGGER.log(WARNING, "Caught: " + e, e);
        }
    }

    @Override
    public <T> void eventReceived(Action action, T resource) {
        ImageStream imageStream = (ImageStream) resource;
        eventReceived(action, imageStream);
    }

    private void onImageStreamInitialization(ImageStreamList imageStreams) {
        if (imageStreams != null) {
            List<ImageStream> items = imageStreams.getItems();
            if (items != null) {
                for (ImageStream imageStream : items) {
                    try {
                        List<PodTemplate> agents = PodTemplateUtils.getPodTemplatesListFromImageStreams(imageStream);
                        for (PodTemplate entry : agents) {
                            // watch event might beat the timer - put call is technically fine, but not
                            // addPodTemplate given k8s plugin issues
                            if (!PodTemplateUtils.hasPodTemplate(entry)) {
                                PodTemplateUtils.addPodTemplate(entry);
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.log(SEVERE, "Failed to update job", e);
                    }
                }
            }
        }
    }

}
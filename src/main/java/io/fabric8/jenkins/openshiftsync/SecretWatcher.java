/**
 * Copyright (C) 2016 Red Hat, Inc.
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

import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC;
import static io.fabric8.jenkins.openshiftsync.Constants.VALUE_SECRET_SYNC;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static java.util.logging.Level.SEVERE;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.openshift.client.OpenShiftClient;

/**
 * Watches {@link Secret} objects in Kubernetes and syncs then to Credentials in
 * Jenkins
 */
public class SecretWatcher extends BaseWatcher {

    private static WatchesBasedSafeTimerTask TIMER_TASK;

    private final class SecretWatcherTimerTask extends WatchesBasedSafeTimerTask {

        private boolean requiresRestart = false;

        @Override
        public void doRun() {
            OpenShiftUtils.initializeOpenShiftClient(GlobalPluginConfiguration.get().getServer());
            if (!CredentialsUtils.hasCredentials()) {
                logger.fine("No Openshift Token credential defined.");
                return;
            }
            for (String ns : namespaces) {
                SecretList secrets = null;
                OpenShiftClient client = getAuthenticatedOpenShiftClient();
                try {
                    logger.fine("listing Secrets resources");
                    secrets = client.secrets().inNamespace(ns)
                            .withLabel(OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC, VALUE_SECRET_SYNC).list();
                    onInitialSecrets(secrets);
                    logger.fine("handled Secrets resources");
                } catch (Exception e) {
                    logger.log(SEVERE, "Failed to load Secrets: " + e, e);
                }
                try {
                    String resourceVersion = "0";
                    if (secrets == null) {
                        logger.warning("Unable to get secret list; impacts resource version used for watch");
                    } else {
                        resourceVersion = secrets.getMetadata().getResourceVersion();
                    }

                    Map<String, Watch> watches = Collections.synchronizedMap(getWatches());
                    Watch watch = watches.get(ns);
                    if (watch == null) {
                        logger.info("creating Secret watch for namespace " + ns + " and resource version"
                                + resourceVersion);
                        WatcherCallback<Secret> watcher = new WatcherCallback<Secret>(SecretWatcher.this, ns);
                        addWatch(ns,
                                client.secrets().inNamespace(ns)
                                        .withLabel(OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC, VALUE_SECRET_SYNC)
                                        .withResourceVersion(resourceVersion).watch(watcher));
                    } else {
                        logger.info("Already existent Secret watch for ns: " + ns + ": watch" + watch);
                    }
                } catch (Exception e) {
                    logger.log(SEVERE, "Failed to load Secrets: " + e, e);
                    this.requiresRestart = true;
                }
            }
            if (requiresRestart) {
                this.closeWatches();
                TIMER_TASK.cancel();
                TIMER_TASK = null;
            }
        }
    }

    private ConcurrentHashMap<String, String> trackedSecrets;
    private final Logger logger = Logger.getLogger(getClass().getName());

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public SecretWatcher(String[] namespaces) {
        super(namespaces);
        this.trackedSecrets = new ConcurrentHashMap<>();
    }

    @Override
    public int getListIntervalInSeconds() {
        return GlobalPluginConfiguration.get().getSecretListInterval();
    }

    private final static Object lock = new Object();

    public WatchesBasedSafeTimerTask getStartTimerTask() {
        if (SecretWatcher.TIMER_TASK == null) {
            synchronized (lock) {
                SecretWatcher.TIMER_TASK = new SecretWatcherTimerTask();
            }
        }
        return SecretWatcher.TIMER_TASK;
    }

    public void start() {
        // lets process the initial state
        super.start();
        logger.info("Now handling startup secrets!!");
    }

    private void onInitialSecrets(SecretList secrets) {
        if (secrets == null)
            return;
        if (trackedSecrets == null)
            trackedSecrets = new ConcurrentHashMap<String, String>();
        List<Secret> items = secrets.getItems();
        if (items != null) {
            for (Secret secret : items) {
                try {
                    if (validSecret(secret) && shouldProcessSecret(secret)) {
                        upsertCredential(secret);
                        trackedSecrets.put(secret.getMetadata().getUid(), secret.getMetadata().getResourceVersion());
                    }
                } catch (Exception e) {
                    logger.log(SEVERE, "Failed to update job", e);
                }
            }
        }
    }

    @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
    public void eventReceived(Action action, Secret secret) {
        try {
            switch (action) {
            case ADDED:
                upsertCredential(secret);
                break;
            case DELETED:
                deleteCredential(secret);
                break;
            case MODIFIED:
                modifyCredential(secret);
                break;
            case ERROR:
                logger.warning("watch for secret " + secret.getMetadata().getName() + " received error event ");
                break;
            default:
                logger.warning(
                        "watch for secret " + secret.getMetadata().getName() + " received unknown event " + action);
                break;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Caught: " + e, e);
        }
    }

    @Override
    public <T> void eventReceived(io.fabric8.kubernetes.client.Watcher.Action action, T resource) {
        Secret secret = (Secret) resource;
        eventReceived(action, secret);
    }

    private void upsertCredential(final Secret secret) throws Exception {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                logger.info("Upserting Secret with Uid " + metadata.getUid() + " with Name " + metadata.getName());
                if (validSecret(secret)) {
                    CredentialsUtils.upsertCredential(secret);
                    trackedSecrets.put(metadata.getUid(), metadata.getResourceVersion());
                }
            }
        }
    }

    private void modifyCredential(Secret secret) throws Exception {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                logger.info("Modifying Secret with Uid " + metadata.getUid() + " with Name " + metadata.getName());
                if (validSecret(secret) && shouldProcessSecret(secret)) {
                    CredentialsUtils.upsertCredential(secret);
                    trackedSecrets.put(metadata.getUid(), metadata.getResourceVersion());
                }
            }
        }
    }

    private boolean validSecret(Secret secret) {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                String name = metadata.getName();
                String namespace = metadata.getNamespace();
                logger.info("Validating Secret with Uid " + metadata.getUid() + " with Name " + name);
                return name != null && !name.isEmpty() && namespace != null && !namespace.isEmpty();
            }
        }
        return false;
    }

    private boolean shouldProcessSecret(Secret secret) {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                String uid = metadata.getUid();
                String rv = metadata.getResourceVersion();
                String savedRV = trackedSecrets.get(uid);
                if (savedRV == null || !savedRV.equals(rv)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void deleteCredential(final Secret secret) throws Exception {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                trackedSecrets.remove(metadata.getUid());
                CredentialsUtils.deleteCredential(secret);
            }
        }
    }
}

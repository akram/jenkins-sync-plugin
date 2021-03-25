/**
 * Copyright (C) 2018 Red Hat, Inc.
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

import java.util.Collections;
import java.util.Map;
import java.util.logging.Logger;

import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;

public class WatcherCallback<T> implements Watcher<T> {
    private final Logger LOGGER = Logger.getLogger(getClass().getName());

    private final BaseWatcher watcher;
    private final String namespace;

    public WatcherCallback(BaseWatcher w, String n) {
        watcher = w;
        namespace = n;
    }

    @Override
    public void eventReceived(io.fabric8.kubernetes.client.Watcher.Action action, T resource) {
        watcher.eventReceived(action, resource);
    }

    @Override
    public void onClose(KubernetesClientException cause) {
        LOGGER.info("WatcherCallback closing watcher: " + watcher + ", cause: " + cause);
        watcher.onClose(cause, namespace);
        Map<String, Watch> watches = Collections.synchronizedMap(watcher.getWatches());
        watches.remove(namespace);
    }
}

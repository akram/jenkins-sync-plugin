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

import static hudson.security.ACL.SYSTEM;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getNamespaceOrUseDefault;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.logging.Level.SEVERE;
import static jenkins.model.Jenkins.ADMINISTER;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;

import hudson.Extension;
import hudson.Util;
import hudson.triggers.SafeTimerTask;
import hudson.util.ListBoxModel;
import io.fabric8.kubernetes.client.KubernetesClientException;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import net.sf.json.JSONObject;
import okhttp3.OkHttpClient;

@Extension
public class GlobalPluginConfiguration extends GlobalConfiguration {

    private static final Logger logger = Logger.getLogger(GlobalPluginConfiguration.class.getName());

    private boolean enabled = true;
    private boolean foldersEnabled = true;
    private boolean syncConfigMaps = true;
    private boolean syncSecrets = true;
    private boolean syncImageStreams = true;
    private boolean syncBuildConfigsAndBuilds = true;

    private String server;
    private String credentialsId = "";
    private String[] namespaces;
    private String jobNamePattern;
    private String skipOrganizationPrefix;
    private String skipBranchSuffix;
    private int buildListInterval = 300;
    private int buildConfigListInterval = 300;
    private int secretListInterval = 300;
    private int configMapListInterval = 300;
    private int imageStreamListInterval = 300;

    private transient BuildWatcher buildWatcher;
    private transient BuildConfigWatcher buildConfigWatcher;
    private transient SecretWatcher secretWatcher;
    private transient ConfigMapWatcher configMapWatcher;
    private transient ImageStreamWatcher imageStreamWatcher;
    private static SafeTimerTask TIMER_TASK;

    @DataBoundConstructor
    public GlobalPluginConfiguration(boolean enable, String server, String namespace, boolean foldersEnabled,
            String credentialsId, String jobNamePattern, String skipOrganizationPrefix, String skipBranchSuffix,
            int buildListInterval, int buildConfigListInterval, int configMapListInterval, int secretListInterval,
            int imageStreamListInterval, boolean syncConfigMaps, boolean syncSecrets, boolean syncImageStreams,
            boolean syncBuildsConfigAndBuilds) {
        this.enabled = enable;
        this.server = server;
        this.namespaces = StringUtils.isBlank(namespace) ? null : namespace.split(" ");
        this.foldersEnabled = foldersEnabled;
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
        this.jobNamePattern = jobNamePattern;
        this.skipOrganizationPrefix = skipOrganizationPrefix;
        this.skipBranchSuffix = skipBranchSuffix;
        this.buildListInterval = buildListInterval;
        this.buildConfigListInterval = buildConfigListInterval;
        this.configMapListInterval = configMapListInterval;
        this.secretListInterval = secretListInterval;
        this.imageStreamListInterval = imageStreamListInterval;
        this.syncConfigMaps = syncConfigMaps;
        this.syncSecrets = syncSecrets;
        this.syncImageStreams = syncImageStreams;
        this.syncBuildConfigsAndBuilds = syncBuildsConfigAndBuilds;
        configChange();
        Logger.getLogger(OkHttpClient.class.getName()).setLevel(Level.FINE);
    }

    public GlobalPluginConfiguration() {
        load();
        configChange();
        // save();
    }

    public static GlobalPluginConfiguration get() {
        return GlobalConfiguration.all().get(GlobalPluginConfiguration.class);
    }

    @Override
    public String getDisplayName() {
        return "OpenShift Jenkins Sync";
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws hudson.model.Descriptor.FormException {
        req.bindJSON(this, json);
        configChange();
        save();
        return true;
    }

    // https://wiki.jenkins-ci.org/display/JENKINS/Credentials+Plugin
    // http://javadoc.jenkins-ci.org/credentials/com/cloudbees/plugins/credentials/common/AbstractIdCredentialsListBoxModel.html
    // https://github.com/jenkinsci/kubernetes-plugin/blob/master/src/main/java/org/csanchez/jenkins/plugins/kubernetes/KubernetesCloud.java
    public static ListBoxModel doFillCredentialsIdItems(String credentialsId) {
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            return (ListBoxModel) null;
        }
        StandardListBoxModel model = new StandardListBoxModel();
        if (!jenkins.hasPermission(ADMINISTER)) {
            // Important! Otherwise you expose credentials metadata to random web requests.
            return model.includeCurrentValue(credentialsId);
        }
        return model.includeEmptyValue().includeAs(SYSTEM, jenkins, OpenShiftToken.class)
                .includeCurrentValue(credentialsId);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSyncConfigMaps() {
        return syncConfigMaps;
    }

    public void setSyncConfigMaps(boolean syncConfigMaps) {
        this.syncConfigMaps = syncConfigMaps;
    }

    public boolean isSyncSecrets() {
        return syncSecrets;
    }

    public void setSyncSecrets(boolean syncSecrets) {
        this.syncSecrets = syncSecrets;
    }

    public boolean isSyncImageStreams() {
        return syncImageStreams;
    }

    public void setSyncImageStreams(boolean syncImageStreams) {
        this.syncImageStreams = syncImageStreams;
    }

    public boolean isSyncBuildConfigsAndBuilds() {
        return syncBuildConfigsAndBuilds;
    }

    public void setSyncBuildConfigsAndBuilds(boolean syncBuildConfigsAndBuilds) {
        this.syncBuildConfigsAndBuilds = syncBuildConfigsAndBuilds;
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    // When Jenkins is reset, credentialsId is strangely set to null. However,
    // credentialsId has no reason to be null.
    public String getCredentialsId() {
        return credentialsId == null ? "" : credentialsId;
    }

    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    public String getNamespace() {
        return namespaces == null ? "" : StringUtils.join(namespaces, " ");
    }

    public void setNamespace(String namespace) {
        this.namespaces = StringUtils.isBlank(namespace) ? null : namespace.split(" ");
    }

    public boolean getFoldersEnabled() {
        return foldersEnabled;
    }

    public void setFoldersEnabled(boolean foldersEnabled) {
        this.foldersEnabled = foldersEnabled;
    }

    public String getJobNamePattern() {
        return jobNamePattern;
    }

    public void setJobNamePattern(String jobNamePattern) {
        this.jobNamePattern = jobNamePattern;
    }

    public String getSkipOrganizationPrefix() {
        return skipOrganizationPrefix;
    }

    public void setSkipOrganizationPrefix(String skipOrganizationPrefix) {
        this.skipOrganizationPrefix = skipOrganizationPrefix;
    }

    public String getSkipBranchSuffix() {
        return skipBranchSuffix;
    }

    public void setSkipBranchSuffix(String skipBranchSuffix) {
        this.skipBranchSuffix = skipBranchSuffix;
    }

    public int getBuildListInterval() {
        return buildListInterval;
    }

    public void setBuildListInterval(int buildListInterval) {
        this.buildListInterval = buildListInterval;
    }

    public int getBuildConfigListInterval() {
        return buildConfigListInterval;
    }

    public void setBuildConfigListInterval(int buildConfigListInterval) {
        this.buildConfigListInterval = buildConfigListInterval;
    }

    public int getSecretListInterval() {
        return secretListInterval;
    }

    public void setSecretListInterval(int secretListInterval) {
        this.secretListInterval = secretListInterval;
    }

    public int getConfigMapListInterval() {
        return configMapListInterval;
    }

    public void setConfigMapListInterval(int configMapListInterval) {
        this.configMapListInterval = configMapListInterval;
    }

    public int getImageStreamListInterval() {
        return imageStreamListInterval;
    }

    public void setImageStreamListInterval(int imageStreamListInterval) {
        this.imageStreamListInterval = imageStreamListInterval;
    }

    String[] getNamespaces() {
        return namespaces;
    }

    void setNamespaces(String[] namespaces) {
        this.namespaces = namespaces;
    }

    void setBuildWatcher(BuildWatcher buildWatcher) {
        this.buildWatcher = buildWatcher;
    }

    void setBuildConfigWatcher(BuildConfigWatcher buildConfigWatcher) {
        this.buildConfigWatcher = buildConfigWatcher;
    }

    void setSecretWatcher(SecretWatcher secretWatcher) {
        this.secretWatcher = secretWatcher;
    }

    void setConfigMapWatcher(ConfigMapWatcher configMapWatcher) {
        this.configMapWatcher = configMapWatcher;
    }

    void setImageStreamWatcher(ImageStreamWatcher imageStreamWatcher) {
        this.imageStreamWatcher = imageStreamWatcher;
    }

    private synchronized void configChange() {
        OpenShiftUtils.initializeOpenShiftClient(this.server);
        logger.info("OpenShift Sync Plugin processing a newly supplied configuration");
        if (this.buildConfigWatcher != null) {
            this.buildConfigWatcher.stop();
        }
        if (this.buildWatcher != null) {
            this.buildWatcher.stop();
        }
        if (this.configMapWatcher != null) {
            this.configMapWatcher.stop();
        }
        if (this.imageStreamWatcher != null) {
            this.imageStreamWatcher.stop();
        }
        if (this.secretWatcher != null) {
            this.secretWatcher.stop();
        }
        this.buildWatcher = null;
        this.buildConfigWatcher = null;
        this.configMapWatcher = null;
        this.imageStreamWatcher = null;
        this.secretWatcher = null;
        // OpenShiftUtils.shutdownOpenShiftClient();
//        DefaultOpenShiftClient client = (DefaultOpenShiftClient) getOpenShiftClient();
//        client.getHttpClient().dispatcher().cancelAll();

        if (!this.enabled) {
            logger.info("OpenShift Sync Plugin has been disabled");
            return;
        }
        try {
            OpenShiftUtils.initializeOpenShiftClient(this.server);
            this.namespaces = getNamespaceOrUseDefault(this.namespaces, getOpenShiftClient());
            if (TIMER_TASK != null) {
                logger.info("Cancelling previous GlobalPluginConfigurationTimerTask: " + TIMER_TASK);
                TIMER_TASK.cancel();
                // Timer.get().shutdown(); // This breaks Jenkins
                TIMER_TASK = null;
            }
            TIMER_TASK = new GlobalPluginConfigurationTimerTask(this);
            Timer.get().schedule(TIMER_TASK, 1, SECONDS); // lets give jenkins a while to get started ;)
        } catch (KubernetesClientException e) {
            Throwable exceptionOrCause = (e.getCause() != null) ? e.getCause() : e;
            logger.log(SEVERE, "Failed to configure OpenShift Jenkins Sync Plugin: " + exceptionOrCause);
        }
    }

    public BuildWatcher getBuildWatcher() {
        return buildWatcher;
    }

    public BuildConfigWatcher getBuildConfigWatcher() {
        return buildConfigWatcher;
    }

    public SecretWatcher getSecretWatcher() {
        return secretWatcher;
    }

    public ConfigMapWatcher getConfigMapWatcher() {
        return configMapWatcher;
    }

    public ImageStreamWatcher getImageStreamWatcher() {
        return imageStreamWatcher;
    }

}

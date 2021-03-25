package io.fabric8.jenkins.openshiftsync;

import static hudson.init.InitMilestone.COMPLETED;

import java.util.logging.Logger;

import hudson.init.InitMilestone;
import hudson.triggers.SafeTimerTask;
import jenkins.model.Jenkins;

public class GlobalPluginConfigurationTimerTask extends SafeTimerTask {

    private static final Logger logger = Logger.getLogger(GlobalPluginConfigurationTimerTask.class.getName());

    private GlobalPluginConfiguration globalPluginConfiguration;

    public GlobalPluginConfigurationTimerTask(GlobalPluginConfiguration globalPluginConfiguration) {
        this.globalPluginConfiguration = globalPluginConfiguration;
    }

    @Override
    protected void doRun() throws Exception {
        logger.info("Confirming Jenkins is started");
        while (true) {
            final Jenkins instance = Jenkins.getActiveInstance();
            // We can look at Jenkins Init Level to see if we are ready to start. If we do
            // not wait, we risk the chance of a deadlock.
            InitMilestone initLevel = instance.getInitLevel();
            logger.fine("Jenkins init level: " + initLevel);
            if (initLevel == COMPLETED) {
                break;
            }
            logger.fine("Jenkins not ready...");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        intializeAndStartWatchers();
    }

    private void intializeAndStartWatchers() {
        OpenShiftUtils.shutdownOpenShiftClient();
        String[] namespaces = globalPluginConfiguration.getNamespaces();
        initBuildConfigAndBuildWatcher(namespaces);
        initConfigMapWatcher(namespaces);
        initImageStreampWatcher(namespaces);
        initSecretWatcher(namespaces);
    }

    private void initSecretWatcher(String[] namespaces) {
        SecretWatcher watcher = globalPluginConfiguration.getSecretWatcher();
        if (watcher != null) {
            watcher.stop();
            globalPluginConfiguration.setSecretWatcher(null);
        }
        if (globalPluginConfiguration.isSyncSecrets()) {
            watcher = new SecretWatcher(namespaces);
            globalPluginConfiguration.setSecretWatcher(watcher);
            watcher.start();
        }
    }

    private void initImageStreampWatcher(String[] namespaces) {
        ImageStreamWatcher watcher = globalPluginConfiguration.getImageStreamWatcher();
        if (watcher != null) {
            watcher.stop();
            globalPluginConfiguration.setConfigMapWatcher(null);
        }
        if (globalPluginConfiguration.isSyncImageStreams()) {
            watcher = new ImageStreamWatcher(namespaces);
            globalPluginConfiguration.setImageStreamWatcher(watcher);
            watcher.start();
        }
    }

    private void initConfigMapWatcher(String[] namespaces) {
        if (globalPluginConfiguration.isSyncConfigMaps()) {
            ConfigMapWatcher watcher = globalPluginConfiguration.getConfigMapWatcher();
            if (watcher != null) {
                watcher.stop();
                globalPluginConfiguration.setConfigMapWatcher(null);
            }
            watcher = new ConfigMapWatcher(namespaces);
            globalPluginConfiguration.setConfigMapWatcher(watcher);
            watcher.start();
        }
    }

    private void initBuildConfigAndBuildWatcher(String[] namespaces) {
        BuildWatcher buildWatcher = globalPluginConfiguration.getBuildWatcher();
        if (buildWatcher != null) {
            buildWatcher.stop();
            globalPluginConfiguration.setBuildWatcher(null);

        }
        BuildConfigWatcher buildConfigWatcher = globalPluginConfiguration.getBuildConfigWatcher();
        if (buildConfigWatcher != null) {
            buildConfigWatcher.stop();
            globalPluginConfiguration.setBuildConfigWatcher(null);
        }

        if (globalPluginConfiguration.isSyncBuildConfigsAndBuilds()) {
            buildConfigWatcher = new BuildConfigWatcher(namespaces);
            globalPluginConfiguration.setBuildConfigWatcher(buildConfigWatcher);
            buildConfigWatcher.start();

            buildWatcher = new BuildWatcher(namespaces);
            globalPluginConfiguration.setBuildWatcher(buildWatcher);
            buildWatcher.start();
        }
    }
}

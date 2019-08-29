package io.fabric8.jenkins.openshiftsync;


import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.AbortException;
import hudson.BulkChange;
import hudson.model.ItemGroup;
import hudson.model.ParameterDefinition;
import hudson.model.listeners.RunListener;
import hudson.util.XStream2;
import io.fabric8.openshift.api.model.BuildConfig;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;
import org.apache.tools.ant.filters.StringInputStream;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.Annotations.DISABLE_SYNC_CREATE;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.getJobFromBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.putJobWithBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMapper.mapBuildConfigToFlow;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL_LATEST_ONLY;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.DISABLE_PRUNE_PREFIX;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.updateJob;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAnnotation;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getFullNameParent;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getNamespace;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isJobPruningDisabled;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.jenkinsJobDisplayName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.jenkinsJobFullName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.jenkinsJobName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.parseResourceVersion;

public class JobProcessor extends NotReallyRoleSensitiveCallable<Void, Exception> {

	private final BuildConfigWatcher jobProcessor;
	private final BuildConfig buildConfig;
  private final static Logger logger = Logger.getLogger(BuildConfigToJobMap.class.getName());

	public JobProcessor(BuildConfigWatcher buildConfigWatcher, BuildConfig buildConfig) {
		jobProcessor = buildConfigWatcher;
		this.buildConfig = buildConfig;
	}

	@Override
	public Void call() throws Exception {
		Jenkins activeInstance = Jenkins.getActiveInstance();
		ItemGroup parent = activeInstance;
		
		String jobName = jenkinsJobName(buildConfig);
		String jobFullName = jenkinsJobFullName(buildConfig);
		WorkflowJob job = getJobFromBuildConfig(buildConfig);
		
		if (job == null) {
			job = (WorkflowJob) activeInstance.getItemByFullName(jobFullName);
		}
		boolean newJob = job == null;
		
		if (newJob) {
			String disableOn = getAnnotation(buildConfig, DISABLE_SYNC_CREATE);
			if (disableOn != null && disableOn.length() > 0) {
				logger.fine("Not creating missing jenkins job " + jobFullName + " due to annotation: "
						+ DISABLE_SYNC_CREATE);
				return null;
			}
			parent = getFullNameParent(activeInstance, jobFullName, getNamespace(buildConfig));
			job = new WorkflowJob(parent, jobName);
		}
		BulkChange bulkJob = new BulkChange(job);

		job.setDisplayName(jenkinsJobDisplayName(buildConfig));

		FlowDefinition flowFromBuildConfig = mapBuildConfigToFlow(buildConfig);
		if (flowFromBuildConfig == null) {
			return null;
		}
		Map<String, ParameterDefinition> paramMap = createOrUpdateJob(activeInstance, parent, jobName, job, newJob,
				flowFromBuildConfig);
		bulkJob.commit();
		populateNamespaceFolder(activeInstance, parent, jobName, job, paramMap);
		return null;
	}

	private void populateNamespaceFolder(Jenkins activeInstance, ItemGroup parent, String jobName, WorkflowJob job,
			Map<String, ParameterDefinition> paramMap) throws IOException, AbortException {
		String fullName = job.getFullName();
		WorkflowJob workflowJob = activeInstance.getItemByFullName(fullName, WorkflowJob.class);
		if (workflowJob == null && parent instanceof Folder) {
			// we should never need this but just in
			// case there's an
			// odd timing issue or something...
			Folder folder = (Folder) parent;
			folder.add(job, jobName);
			workflowJob = activeInstance.getItemByFullName(fullName, WorkflowJob.class);

		}
		if (workflowJob == null) {
			logger.warning("Could not find created job " + fullName + " for BuildConfig: " + getNamespace(buildConfig)
					+ "/" + getName(buildConfig));
		} else {
			JenkinsUtils.verifyEnvVars(paramMap, workflowJob, buildConfig);
			putJobWithBuildConfig(workflowJob, buildConfig);
		}
	}

	public BuildConfigProjectProperty updateBuildConfigUid(BuildConfigProjectProperty buildConfigProjectProperty, BuildConfigProjectProperty newProperty){
    String uid = buildConfigProjectProperty.getUid();
    String newUid = newProperty.getUid();
    if (!uid.startsWith(DISABLE_PRUNE_PREFIX)){
      buildConfigProjectProperty.setUid(newUid);
    }
    if (isJobPruningDisabled(buildConfigProjectProperty) && !uid.contains(DISABLE_PRUNE_PREFIX)) {
      String pruneDisabledUid = DISABLE_PRUNE_PREFIX+uid;
      buildConfigProjectProperty.setUid(pruneDisabledUid);
      logger.info("Prune disabled on Jenkins Job " + jenkinsJobFullName(buildConfig));
      pollRunsForJob(1);
    }

    String oldUID = uid.replace(DISABLE_PRUNE_PREFIX,"");
    String oldName = buildConfigProjectProperty.getNamespace() + "/" + buildConfigProjectProperty.getNamespace() + "-" + buildConfigProjectProperty.getName();

    if (uid.startsWith(DISABLE_PRUNE_PREFIX) && !oldUID.equals(newUid) && oldName.equals(jenkinsJobFullName(buildConfig))){
      buildConfigProjectProperty.setUid(newUid);
      logger.info("Migrated Jenkins Job "+jenkinsJobFullName(buildConfig));
      pollRunsForJob(1);
    }

    return buildConfigProjectProperty;
  }

  public void pollRunsForJob(int retry) {
    BuildSyncRunListener runListener = null;
    for (RunListener rl : BuildSyncRunListener.all()){
      if (rl instanceof BuildSyncRunListener){
        runListener = (BuildSyncRunListener) rl;
        break;
      }
    }
    if (runListener != null){
      WorkflowJob job = getJobFromBuildConfig(buildConfig);
      for (WorkflowRun run : job.getBuilds()){
        logger.info("pollRunForJob updating Run "+run.toString());
        runListener.pollRun(run);
      }
    }
    retry--;
    if (retry > 0){
      pollRunsForJob(retry);
    }
  }

  private Map<String, ParameterDefinition> createOrUpdateJob(Jenkins activeInstance, ItemGroup parent, String jobName,
			WorkflowJob job, boolean newJob, FlowDefinition flowFromBuildConfig) throws Exception {
		job.setDefinition(flowFromBuildConfig);

		String existingBuildRunPolicy = null;

		BuildConfigProjectProperty buildConfigProjectProperty = job.getProperty(BuildConfigProjectProperty.class);
    existingBuildRunPolicy = populateBCProjectProperty(job, existingBuildRunPolicy, buildConfigProjectProperty);

		// (re)populate job param list with any envs
		// from the build config
		Map<String, ParameterDefinition> paramMap = JenkinsUtils.addJobParamForBuildEnvs(job,
				buildConfig.getSpec().getStrategy().getJenkinsPipelineStrategy(), true);

		job.setConcurrentBuild(!(buildConfig.getSpec().getRunPolicy().equals(SERIAL)
				|| buildConfig.getSpec().getRunPolicy().equals(SERIAL_LATEST_ONLY)));

		InputStream jobStream = new StringInputStream(new XStream2().toXML(job));

		if (newJob) {
			try {
				if (parent instanceof Folder) {
					Folder folder = (Folder) parent;
					folder.createProjectFromXML(jobName, jobStream).save();
				} else {
					activeInstance.createProjectFromXML(jobName, jobStream).save();
				}

				logger.info("Created job " + jobName + " from BuildConfig " + NamespaceName.create(buildConfig)
						+ " with revision: " + buildConfig.getMetadata().getResourceVersion());
			} catch (IllegalArgumentException e) {
				// see
				// https://github.com/openshift/jenkins-sync-plugin/issues/117,
				// jenkins might reload existing jobs on
				// startup between the
				// newJob check above and when we make
				// the createProjectFromXML call; if so,
				// retry as an update
				updateJob(job, jobStream, existingBuildRunPolicy, buildConfigProjectProperty);
				logger.info("Updated job " + jobName + " from BuildConfig " + NamespaceName.create(buildConfig)
						+ " with revision: " + buildConfig.getMetadata().getResourceVersion());
			}
		} else {
			updateJob(job, jobStream, existingBuildRunPolicy, buildConfigProjectProperty);
			logger.info("Updated job " + jobName + " from BuildConfig " + NamespaceName.create(buildConfig)
					+ " with revision: " + buildConfig.getMetadata().getResourceVersion());
		}
		return paramMap;
	}

	private String populateBCProjectProperty(WorkflowJob job, String existingBuildRunPolicy,
			BuildConfigProjectProperty buildConfigProjectProperty) throws Exception {
		if (buildConfigProjectProperty != null) {

			existingBuildRunPolicy = buildConfigProjectProperty.getBuildRunPolicy();

			long updatedBCResourceVersion = parseResourceVersion(buildConfig);
			long oldBCResourceVersion = parseResourceVersion(buildConfigProjectProperty.getResourceVersion());

			BuildConfigProjectProperty newProperty = new BuildConfigProjectProperty(buildConfig);

			if (updatedBCResourceVersion <= oldBCResourceVersion
					&& newProperty.getUid().equals(buildConfigProjectProperty.getUid())
					&& newProperty.getNamespace().equals(buildConfigProjectProperty.getNamespace())
					&& newProperty.getName().equals(buildConfigProjectProperty.getName())
					&& newProperty.getBuildRunPolicy().equals(buildConfigProjectProperty.getBuildRunPolicy())) {
				return null;
			}

      updateBuildConfigUid(buildConfigProjectProperty, newProperty);
      buildConfigProjectProperty.setNamespace(newProperty.getNamespace());
			buildConfigProjectProperty.setName(newProperty.getName());
			buildConfigProjectProperty.setResourceVersion(newProperty.getResourceVersion());
			buildConfigProjectProperty.setBuildRunPolicy(newProperty.getBuildRunPolicy());

		} else {
		  job.addProperty(new BuildConfigProjectProperty(buildConfig));
		}
		return existingBuildRunPolicy;
	}

}


package com.openshift.jenkins.plugins.pipeline.model;

import com.openshift.jenkins.plugins.pipeline.MessageConstants;
import com.openshift.jenkins.plugins.pipeline.NameValuePair;
import com.openshift.jenkins.plugins.pipeline.OpenShiftBuildCanceller;
import com.openshift.jenkins.support.PodLogWatcher;
import com.openshift.jenkins.support.ResourceWatcher;
import com.openshift.restclient.IClient;
import com.openshift.restclient.IOpenShiftWatchListener;
import com.openshift.restclient.ResourceKind;
import com.openshift.restclient.capability.CapabilityVisitor;
import com.openshift.restclient.capability.IStoppable;
import com.openshift.restclient.capability.resources.IBuildTriggerable;
import com.openshift.restclient.model.IBuild;
import com.openshift.restclient.model.IBuildConfig;
import com.openshift.restclient.model.IPod;
import com.openshift.restclient.model.IResource;

import hudson.Launcher;
import hudson.model.TaskListener;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;

public interface IOpenShiftBuilder extends ITimedOpenShiftPlugin {

    String DISPLAY_NAME = "Trigger OpenShift Build";

    default String getDisplayName() {
        return DISPLAY_NAME;
    }

    String getCommitID();

    String getBuildName();

    String getShowBuildLogs();

    String getBldCfg();

    String getCheckForTriggeredDeployments();

    List<NameValuePair> getEnv();

    default String getCommitID(Map<String, String> overrides) {
        return getOverride(getCommitID(), overrides);
    }

    default String getBuildName(Map<String, String> overrides) {
        return getOverride(getBuildName(), overrides);
    }

    default String getShowBuildLogs(Map<String, String> overrides) {
        return getOverride(getShowBuildLogs(), overrides);
    }

    default String getBldCfg(Map<String, String> overrides) {
        return getOverride(getBldCfg(), overrides);
    }

    default String getCheckForTriggeredDeployments(Map<String, String> overrides) {
        return getOverride(getCheckForTriggeredDeployments(), overrides);
    }

    default long getGlobalTimeoutConfiguration() {
        return GlobalConfig.getBuildWait();
    }

    default void applyEnvVars(IBuildTriggerable bt) {
        if (getEnv() != null) {
            for (NameValuePair p : getEnv()) {
                String name = p.getName().trim();
                if (!name.isEmpty()) {
                    bt.setEnvironmentVariable(p.getName(), p.getValue());
                }
            }
        }
    }

    default IBuild startBuild(IBuildConfig bc, IBuild prevBld, Map<String, String> overrides, boolean chatty, TaskListener listener, IClient client) {
        IBuild bld = null;
        if (bc != null) {
            if (getCommitID(overrides) != null && getCommitID(overrides).length() > 0) {
                final String cid = getCommitID(overrides);
                bld = bc.accept(new CapabilityVisitor<IBuildTriggerable, IBuild>() {
                    public IBuild visit(IBuildTriggerable triggerable) {
                        triggerable.setCommitId(cid);
                        triggerable.addBuildCause("Jenkins job URI: " + overrides.get(IOpenShiftPlugin.BUILD_URL_ENV_KEY));
                        applyEnvVars(triggerable);
                        return triggerable.trigger();
                    }
                }, null);
            } else {
                bld = bc.accept(new CapabilityVisitor<IBuildTriggerable, IBuild>() {
                    public IBuild visit(IBuildTriggerable triggerable) {
                        triggerable.addBuildCause("Jenkins job URI: " + overrides.get(IOpenShiftPlugin.BUILD_URL_ENV_KEY));
                        applyEnvVars(triggerable);
                        return triggerable.trigger();
                    }
                }, null);
            }
        } else if (prevBld != null) {
            bld = prevBld.accept(new CapabilityVisitor<IBuildTriggerable, IBuild>() {
                public IBuild visit(IBuildTriggerable triggerable) {
                    triggerable.addBuildCause("Jenkins job URI: " + overrides.get(IOpenShiftPlugin.BUILD_URL_ENV_KEY));
                    applyEnvVars(triggerable);
                    return triggerable.trigger();
                }
            }, null);
        }
        return bld;
    }

    default void waitOnBuild(IClient client, long startTime, String bldId, TaskListener listener, Map<String, String> overrides, long wait, boolean follow, boolean chatty, IPod pod) throws InterruptedException{
        // get internal OS Java REST Client error if access pod logs while bld is in Pending state
        // instead of Running, Complete, or Failed
        
        final Duration maxWait = Duration.ofMillis(wait);
        final PodLogWatcher logWatcher = new PodLogWatcher(listener, follow, pod, startTime, maxWait);
        IStoppable stop = logWatcher.watch();
        final CountDownLatch latch = new CountDownLatch(1);
        
        final IStoppable watcher = new ResourceWatcher(startTime, maxWait, client, getNamespace(overrides), ResourceKind.BUILD, new IOpenShiftWatchListener.OpenShiftWatchListenerAdapter() {

            @Override
            public void received(IResource resource, ChangeType change) {
                IBuild build = (IBuild)resource;
                String bldState = build.getStatus();
                if (Boolean.parseBoolean(getVerbose(overrides)))
                    listener.getLogger().println("\nOpenShiftBuilder bld state:  " + bldState);
                if(isBuildFinished(bldState)) {
                    latch.countDown();
                }
            }
            
        }).watch();
        
        try {
            latch.await(startTime + wait, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // need to throw as this indicates the step as been cancelled
            // also attempt to cancel build on openshift side
            OpenShiftBuildCanceller canceller = new OpenShiftBuildCanceller(getApiURL(overrides), getNamespace(overrides), getAuthToken(overrides), getVerbose(overrides), getBldCfg(overrides));
            canceller.setAuth(getAuth());
            canceller.coreLogic(null, listener, overrides);
            throw e;
        }finally {
            if(watcher != null) {
                watcher.stop();
            }
            if (stop != null)
                stop.stop();
        }

    }

    default boolean coreLogic(Launcher launcher, TaskListener listener, Map<String, String> overrides) throws InterruptedException {
        boolean chatty = Boolean.parseBoolean(getVerbose(overrides));
        boolean checkDeps = Boolean.parseBoolean(getCheckForTriggeredDeployments(overrides));
        listener.getLogger().println(String.format(MessageConstants.START_BUILD_RELATED_PLUGINS, DISPLAY_NAME, getBldCfg(overrides), getNamespace(overrides)));

        boolean follow = Boolean.parseBoolean(getShowBuildLogs(overrides));
        if (chatty)
            listener.getLogger().println("\nOpenShiftBuilder logger follow " + follow);

        // get oc client
        IClient client = this.getClient(listener, DISPLAY_NAME, overrides);

        if (client != null) {
            long startTime = System.currentTimeMillis();
            boolean skipBC = StringUtils.isNotBlank(getBuildName(overrides));
            IBuildConfig bc = null;
            IBuild prevBld = null;
            if (!skipBC) {
                bc = client.get(ResourceKind.BUILD_CONFIG, getBldCfg(overrides), getNamespace(overrides));
            } else {
                prevBld = client.get(ResourceKind.BUILD, getBuildName(overrides), getNamespace(overrides));
            }


            if (chatty)
                listener.getLogger().println("\nOpenShiftBuilder build config retrieved " + bc + " buildName " + getBuildName(overrides));

            if (bc != null || prevBld != null) {

                // Trigger / start build
                IBuild bld = this.startBuild(bc, prevBld, overrides, chatty, listener, client);


                if (bld == null) {
                    listener.getLogger().println(MessageConstants.EXIT_BUILD_NO_BUILD_OBJ);
                    return false;
                } else {
                    annotateJobInfoToResource(client, listener, chatty, overrides, bld);

                    String bldId = bld.getName();
                    if (!checkDeps)
                        listener.getLogger().println(String.format(MessageConstants.WAITING_ON_BUILD, bldId));
                    else
                        listener.getLogger().println(String.format(MessageConstants.WAITING_ON_BUILD_PLUS_DEPLOY, bldId));

                    boolean foundPod = false;
                    startTime = System.currentTimeMillis();

                    long wait = getTimeout(listener, chatty, overrides);

                    if (chatty)
                        listener.getLogger().println("\n OpenShiftBuilder looking for build " + bldId);

                    // Now find build Pod, attempt to dump the logs to the Jenkins console
                    while (!foundPod && startTime > (System.currentTimeMillis() - wait)) {

                        // fetch current list of pods ... this has proven to not be immediate in finding latest
                        // entries when compared with say running oc from the cmd line
                        List<IPod> pods = client.list(ResourceKind.POD, getNamespace(overrides));
                        for (IPod pod : pods) {
                            if (chatty)
                                listener.getLogger().println("\nOpenShiftBuilder found pod " + pod.getName());

                            // build pod starts with build id
                            if (pod.getName().startsWith(bldId)) {
                                foundPod = true;
                                if (chatty)
                                    listener.getLogger().println("\nOpenShiftBuilder found build pod " + pod);

                                waitOnBuild(client, startTime, bldId, listener, overrides, wait, follow, chatty, pod);

                            }

                            if (foundPod)
                                break;
                        }

                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            // need to throw as this indicates the step as been cancelled
                            // also attempt to cancel build on openshift side
                            OpenShiftBuildCanceller canceller = new OpenShiftBuildCanceller(getApiURL(overrides), getNamespace(overrides), getAuthToken(overrides), getVerbose(overrides), getBldCfg(overrides));
                            canceller.setAuth(getAuth());
                            canceller.coreLogic(null, listener, overrides);
                            throw e;
                        }

                    }

                    if (!foundPod) {
                        listener.getLogger().println(String.format(MessageConstants.EXIT_BUILD_NO_POD_OBJ, bldId));
                        return false;
                    }

                    return this.verifyBuild(startTime, wait, client, getBldCfg(overrides), bldId, getNamespace(overrides), chatty, listener, DISPLAY_NAME, checkDeps, true, overrides);

                }


            } else {
                listener.getLogger().println(String.format(MessageConstants.EXIT_BUILD_NO_BUILD_CONFIG_OBJ, getBldCfg(overrides)));
                return false;
            }
        } else {
            return false;
        }

    }
}

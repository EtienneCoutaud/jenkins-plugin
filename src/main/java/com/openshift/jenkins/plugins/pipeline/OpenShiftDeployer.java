package com.openshift.jenkins.plugins.pipeline;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.model.TaskListener;
import hudson.model.AbstractProject;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import com.openshift.internal.restclient.model.ReplicationController;
import com.openshift.restclient.ClientFactory;
import com.openshift.restclient.IClient;
import com.openshift.restclient.ResourceKind;
import com.openshift.restclient.model.IDeploymentConfig;
import com.openshift.restclient.model.IReplicationController;

import javax.servlet.ServletException;

import java.io.IOException;

public class OpenShiftDeployer extends OpenShiftBaseStep {

    protected String depCfg = "frontend";
    
    
    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public OpenShiftDeployer(String apiURL, String depCfg, String namespace, String authToken, String verbose) {
        this.apiURL = apiURL;
        this.depCfg = depCfg;
        this.namespace = namespace;
        this.authToken = authToken;
        this.verbose = verbose;
    }

	public String getDepCfg() {
		return depCfg;
	}

	
	@Override
	protected boolean coreLogic(Launcher launcher, TaskListener listener,
			EnvVars env) {
		boolean chatty = Boolean.parseBoolean(verbose);
    	listener.getLogger().println("\n\nBUILD STEP:  OpenShiftDeployer in perform for " + depCfg + " on namespace " + namespace);
    	
    	// get oc client (sometime REST, sometimes Exec of oc command
    	IClient client = new ClientFactory().create(apiURL, auth);
    	
    	if (client != null) {
    		// seed the auth
        	client.setAuthorizationStrategy(bearerToken);
        	
        	
        	// do the oc deploy ... may need to retry
        	long currTime = System.currentTimeMillis();
        	boolean deployDone = false;
        	if (chatty)
        		listener.getLogger().println("\nOpenShiftDeployer wait " + getDescriptor().getWait());
			int latestVersion =  -1;
        	while (System.currentTimeMillis() < (currTime + getDescriptor().getWait())) {
        		IDeploymentConfig dc = client.get(ResourceKind.DEPLOYMENT_CONFIG, depCfg, namespace);
        		if (dc != null) {
        			if (latestVersion == -1) {
        				latestVersion = dc.getLatestVersionNumber() +1;
        				try {
        					dc.setLatestVersionNumber(latestVersion);
        					client.update(dc);
        				} catch (Throwable t) {
        					if (chatty)
        						t.printStackTrace(listener.getLogger());
        				}
        			}
        			
        			if (chatty) 
        				listener.getLogger().println("\nOpenShiftDeployer latest version now " + latestVersion);

    				try {
    					IReplicationController rc = client.get(ResourceKind.REPLICATION_CONTROLLER, depCfg + "-" + latestVersion, namespace);
    					if (chatty)
    						listener.getLogger().println("\nOpenShiftDeployer returned rep ctrl " + rc);
    					if (rc != null) {
    						String state = rc.getAnnotation("openshift.io/deployment.phase");
    						if (state.equalsIgnoreCase("Complete")) {
    							if (chatty)
    								listener.getLogger().println("\nOpenShiftDeploy phase complete.");
            					deployDone = true;
    						} else if (state.equalsIgnoreCase("Failed")) {
    							listener.getLogger().println("\n\nBUILD STEP EXIT: OpenShiftDeployer got Failed status for ReplicationController " + rc.getName());
    							return false;
    						} else {
    							if (chatty)
    								listener.getLogger().println("\nOpenShiftDeploy current phase " + state);
    						}
    					} else {
    						if (chatty)
    							listener.getLogger().println("\nOpenShiftDeploy no rc for latest version yet");
    					}
    				} catch (Throwable t) {
    					if (chatty)
    						t.printStackTrace(listener.getLogger());
    				}
    				
					
					if (deployDone) {
						break;
					} else {
						if (chatty)
	        				listener.getLogger().println("\nOpenShiftDeployer wait 10 seconds, then try oc deploy again");
						try {
							Thread.sleep(10000);
						} catch (InterruptedException e) {
						}
					}
        				
        			
        		}
        	}
        	
        	if (!deployDone) {
        		listener.getLogger().println("\n\nBUILD STEP EXIT:  OpenShiftDeployer did not succeed in time");
        		return false;
        	}
        	
        	
    	} else {
    		listener.getLogger().println("\n\nBUILD STEP EXIT:  OpenShiftDeployer could not connect to the server");
    		return false;
    	}

    	listener.getLogger().println("\n\nBUILD STEP EXIT:  OpenShiftDeployer exit successfully");
    	return true;
	}

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link OpenShiftDeployer}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
    	private long wait = 60000;
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use <tt>transient</tt>.
         */

        /**
         * In order to load the persisted global configuration, you have to 
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        /**
         * Performs on-the-fly validation of the various fields.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         *      <p>
         *      Note that returning {@link FormValidation#error(String)} does not
         *      prevent the form from being saved. It just means that a message
         *      will be displayed to the user. 
         */
        public FormValidation doCheckApiURL(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set apiURL");
            return FormValidation.ok();
        }

        public FormValidation doCheckDepCfg(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set depCfg");
            return FormValidation.ok();
        }

        public FormValidation doCheckNamespace(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set namespace");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Trigger a deployment in OpenShift";
        }
        
        public long getWait() {
        	return wait;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // pull info from formData, set appropriate instance field (which should have a getter), and call save().
        	wait = formData.getLong("wait");
            save();
            return super.configure(req,formData);
        }

    }

}


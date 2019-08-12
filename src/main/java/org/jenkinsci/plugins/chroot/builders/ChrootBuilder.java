/*
 *  Copyright 2013, Roman Mohr <roman@fenkhuber.at>
 *
 *  This file is part of Chroot-plugin.
 *
 *  Chroot-plugin is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Chroot-plugin is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Chroot-plugin.  If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins.chroot.builders;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import javax.annotation.CheckForNull;
import javax.servlet.ServletException;
import jenkins.tasks.SimpleBuildStep;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.chroot.tools.ChrootToolset;
import org.jenkinsci.plugins.chroot.util.ChrootUtil;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundSetter;

/**
 *
 * @author roman
 */
public class ChrootBuilder extends Builder implements Serializable, SimpleBuildStep {

    private final String chrootName;
    private boolean ignoreExit;
    private List<String> additionalPackages = new ArrayList<>();
    private String packagesFile;
    private String bindMounts;
    private final String command;
    private boolean loginAsRoot;
    private boolean noUpdate;
    private boolean forceInstall;

    @DataBoundSetter
    public void setForceInstall(boolean forceInstall) {
        this.forceInstall = forceInstall;
    }
    
    public boolean isForceInstall() {
        return forceInstall;
    }

    @DataBoundSetter
    public void setNoUpdate(boolean noUpdate) {
        this.noUpdate = noUpdate;
    }
    
    public boolean isNoUpdate() {
        return noUpdate;
    }

    @DataBoundConstructor
    public ChrootBuilder(String chrootName, String command) throws IOException {
        this.chrootName = Util.fixNull(chrootName);
        this.command = Util.fixNull(command);
    }

    @DataBoundSetter
    public void setLoginAsRoot(boolean loginAsRoot) {
        this.loginAsRoot = loginAsRoot;
    }
    
    public boolean isLoginAsRoot() {
        return loginAsRoot;
    }

    public String getChrootName() {
        return chrootName;
    }

    public String getCommand() {
        return command;
    }

    @DataBoundSetter
    public void setIgnoreExit(boolean ignoreExit) {
        this.ignoreExit = ignoreExit;
    }
    
    public boolean isIgnoreExit() {
        return ignoreExit;
    }

    @DataBoundSetter
    public void setAdditionalPackages(@CheckForNull String additionalPackages) {
        this.additionalPackages = ChrootUtil.splitPackages(Util.fixNull(additionalPackages));
    }
    
    public String getAdditionalPackages() {
        return Util.fixEmptyAndTrim(StringUtils.join(additionalPackages, " "));
    }

    @DataBoundSetter
    public void setPackagesFile(@CheckForNull String packagesFile) {
        this.packagesFile = Util.fixNull(packagesFile);
    }
    
    public String getPackagesFile() {
        return Util.fixEmptyAndTrim(packagesFile);
    }
    
    @DataBoundSetter
    public void setBindMounts(@CheckForNull String bindMounts) {
        this.bindMounts = Util.fixNull(bindMounts);
    }

    public String getBindMounts() {
        return bindMounts;
    }

    @Override
    public void perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
        EnvVars env = build.getEnvironment(listener);
        ChrootToolset installation = ChrootToolset.getInstallationByName(env.expand(this.chrootName));
        installation = installation.forNode(workspace.toComputer().getNode(), listener);
        installation = installation.forEnvironment(env);
        if (installation.getHome() == null) {
            listener.fatalError("Installation of chroot environment failed");
            listener.fatalError("Please check if pbuilder is installed on the selected node, and that"
                    + " the user Jenkins uses can run pbuilder with sudo.");
            // return false;
            throw new IOException("Failure");
        }
        FilePath basePath = new FilePath(launcher.getChannel(), installation.getHome());

        //install extra packages
        List<String> packages = new LinkedList<String>(this.additionalPackages);
        for (String packagesFile : ChrootUtil.splitFiles(getPackagesFile())) {
            FilePath packageFile = new FilePath(workspace, packagesFile);
            if (packageFile.exists() && !packageFile.isDirectory()) {
                String packageFilePackages = packageFile.readToString();
                packages.addAll(ChrootUtil.splitPackages(packageFilePackages));
            } else {
                listener.error("Requirements file '" + packagesFile + "' is not an existing file.");
                // return false || ignoreExit;
                throw new IOException("Failure");
            }
        }

        if (!packages.isEmpty()) {
            boolean ret = installation.getChrootWorker().installPackages(build, launcher, listener, basePath, packages, isForceInstall());
            if (ret == false) {
                listener.fatalError("Installing additional packages in chroot environment failed.");
                // return ret || ignoreExit;
                throw new IOException("Failure");
            }
        } else if (!this.isNoUpdate()) {
            boolean ret = installation.getChrootWorker().updateRepositories(build, launcher, listener, basePath);
            if (ret == false) {
                listener.fatalError("Updating repository indices in chroot environment failed.");
                // return ret || ignoreExit;
                throw new IOException("Failure");
            }
        }
        if(!installation.getChrootWorker().perform(build, workspace, launcher, listener, basePath, this.command, this.bindMounts, isLoginAsRoot()) && !ignoreExit)
            throw new IOException();
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public AutoCompletionCandidates doAutoCompleteChrootName(@QueryParameter String value) {
            AutoCompletionCandidates c = new AutoCompletionCandidates();
            for (ChrootToolset set : ChrootToolset.list()) {
                if(set.getName().startsWith(value))
                    c.add(set.getName());
            }
            return c;
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Chroot Builder";
        }

        public FormValidation doCheckPackagesFile(@AncestorInPath AbstractProject project, @QueryParameter String value)
                throws IOException, ServletException, InterruptedException {
            List<String> validationList = new LinkedList<String>();
            Boolean warn = false;
            Boolean error = false;
            FilePath workspace = project.getSomeWorkspace();
            for (String file : ChrootUtil.splitFiles(value)) {
                if (workspace == null) {
                    // return here => exactly one warning, iff field has a value
                    return FormValidation.warning("Workspace does not yet exist.");
                }
                FilePath x = new FilePath(workspace, file);
                if (!x.exists()) {
                    warn = true;
                    validationList.add(String.format("File %s does not yet exist.", file));
                } else if (x.isDirectory()) {
                    error = true;
                    validationList.add(String.format("%s is a directory. Enter a file.", file));
                }
            }
            if (error == true) {
                return FormValidation.error(StringUtils.join(validationList.listIterator(), "\n"));
            } else if (warn == true) {
                return FormValidation.warning(StringUtils.join(validationList.listIterator(), "\n"));
            }
            return FormValidation.ok();
        }
    }
}

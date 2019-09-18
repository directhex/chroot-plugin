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
package org.jenkinsci.plugins.chroot.extensions;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tools.ToolInstallation;
import hudson.util.ArgumentListBuilder;
import hudson.util.QuotedStringTokenizer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.chroot.tools.ChrootToolset;
import org.jenkinsci.plugins.chroot.tools.ChrootToolsetProperty;
import org.jenkinsci.plugins.chroot.tools.Repository;
import org.jenkinsci.plugins.chroot.util.ChrootUtil;

/**
 *
 * @author directhex
 */
@Extension
public class CowbuilderWorker extends ChrootWorker {

    private static final Logger logger = Logger.getLogger("jenkins.plugins.chroot.extensions.CowbuilderWorker");

    @Override
    public String getName() {
        return "cowbuilder";
    }

    @Override
    public String getTool() {
        return "/usr/sbin/cowbuilder";
    }

    private ArgumentListBuilder defaultArgumentList(FilePath basePath, String action) {
        return new ArgumentListBuilder().add("sudo").add(getTool())
                .add(action)
                .add("--basepath").add(basePath.getRemote());
    }

    private boolean doSetUp(FilePath basePath, List<String> packages, ChrootToolsetProperty property, ToolInstallation tool, Node node, TaskListener log) throws IOException, InterruptedException {

        // build setup command
        ArgumentListBuilder cmd = defaultArgumentList(basePath, "--create");

        // don't forget to install additional packages
        if (!getDefaultPackages().isEmpty()) {
            cmd.add("--extrapackages").add(StringUtils.join(packages, " "));
        }

        if (property != null && !Strings.isNullOrEmpty(property.getSetupArguments())) {
            cmd.add(QuotedStringTokenizer.tokenize(property.getSetupArguments()));
        }
        //make pbuilder less verbose by ignoring stdout
        return node.createLauncher(log).launch().cmds(cmd).stderr(log.getLogger()).join() == 0;
    }

    @Override
    public FilePath setUp(ToolInstallation tool, Node node, TaskListener log) throws IOException, InterruptedException {
        FilePath rootDir = node.getRootPath();
        // get path to base path
        FilePath basePath;
        ChrootToolset toolset = ChrootToolset.getInstallationByName(tool.getName());
        ChrootToolsetProperty property = tool.getProperties().get(ChrootToolsetProperty.class);
        String digest = ChrootUtil.getMd5("");
        List<String> pkgList = property.getPackagesList();
        if(pkgList != null)
            digest = ChrootUtil.getMd5(StringUtils.join(property.getPackagesList(), " "));
        basePath = rootDir.child(getName()).child(tool.getName() + "-" + digest + ".cow");

        // run setup
        if (!basePath.exists() || basePath.lastModified() <= toolset.getLastModified()) {

            basePath.deleteRecursive();

            basePath.getParent().mkdirs();
            int ret;

            if (!doSetUp(basePath, getDefaultPackages(), property, tool, node, log)) {
                if (!doSetUp(basePath, getFallbackPackages(), property, tool, node, log)) {
                    log.fatalError("Could not setup chroot environment");
                    return null;
                }
            }
            FilePath script;

            // add repositories
            if (property != null) {
                addRepositories(basePath, node.createLauncher(log), log, property.getRepos());
            }

            // add additional packages
            if (property != null && !property.getPackagesList().isEmpty()) {
                ArgumentListBuilder cmd = defaultArgumentList(basePath, "--update")
                        .add("--extrapackages")
                        .add(StringUtils.join(property.getPackagesList(), " "));
                ret = node.createLauncher(log).launch().cmds(cmd).stderr(log.getLogger()).join();
                if (ret != 0) {
                    log.fatalError("Could not install additional packages.");
                    return null;
                }
            }

            // run additional setup command
            if (property != null && !property.getSetupCommand().isEmpty()) {
                String shebang = "#!/usr/bin/env bash\n";
                String command = shebang + "set -e\nset -x verbose\n" + property.getSetupCommand();
                script = rootDir.createTextTempFile("chroot", ".sh", command);
                ArgumentListBuilder cmd = defaultArgumentList(basePath, "--execute")
                        .add("--save-after-exec")
                        .add("--").add(script);
                ret = node.createLauncher(log).launch().cmds(cmd).stdout(log).stderr(log.getLogger()).join();
                script.delete();
                if (ret != 0) {
                    log.fatalError("Post-setup command failed.");
                    return null;
                }
            }
        }
        return basePath;
    }

    @Override
    public boolean perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener, FilePath basePath, String commands, String bindMounts, boolean runAsRoot) throws IOException, InterruptedException {
        String userName = super.getUserName(launcher);
        String groupName = super.getGroupName(launcher, userName);
        String userHome = workspace.getRemote();
        int id = super.getUID(launcher, userName);
        int gid = super.getGID(launcher, userName);
        EnvVars environment = build.getEnvironment(listener);
        commands = "cd " + workspace.getRemote() + "\n" + commands + "\n";
        commands = "set -e\nset -x verbose\n" + commands;
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            commands = String.format("if [ -z ${%s} ]; then export %s=\"%s\"; fi;\n", entry.getKey(), entry.getKey(), entry.getValue()) + commands;
        }
        FilePath script = workspace.createTextTempFile("chroot", ".sh", commands);
        String create_group = String.format("groupadd -g %d %s | :\n", gid, groupName);
        String create_user = String.format("useradd %s -u %d -g %d -m | : \n", userName, id, gid);
        String run_script;
        String sudoUser = userName;
        if (runAsRoot) {
            sudoUser = "root";
        }

        run_script = String.format("chmod u+x %s\n ret=1; sudo -i -u %s bash -- %s; if [ $? -eq 0 ]; then ret=0; fi;cd %s; chown %s:%s ./ -R; exit $ret\n", script.getRemote(), sudoUser, script.getRemote(), workspace.getRemote(), userName, groupName);

        String shebang = "#!/usr/bin/env bash\n";
        String setup_command = shebang + create_group + create_user + run_script;
        FilePath setup_script = workspace.createTextTempFile("chroot", ".sh", setup_command);
        ArgumentListBuilder b = new ArgumentListBuilder().add("sudo").add(getTool()).add("--execute")
                .add("--bindmounts");
        if(StringUtils.isEmpty(bindMounts)) {
            b.add(userHome);
        }
        else
        {
            StringBuilder allMounts = new StringBuilder().append(userHome).append(" ").append(bindMounts);
            b.add(allMounts.toString());
        }
        ArgumentListBuilder testargs = new ArgumentListBuilder().add("sudo").add(getTool())
                .add("--help");
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try {
            launcher.launch().cmds(testargs).stderr(stderr).stdout(stdout).join();
            if (stdout.toString().contains("--killer")) {
                b.add("--killer");
            }
        } catch (IOException ex) {
            logger.log(Level.SEVERE, null, ex);
        } catch (InterruptedException ex) {
            logger.log(Level.SEVERE, null, ex);
        }
        b.add("--basepath").add(basePath.getRemote())
            .add("--").add(setup_script);
        int exitCode = launcher.launch().cmds(b).envs(environment).stdout(listener).stderr(listener.getLogger()).join();
        script.delete();
        setup_script.delete();
        return exitCode == 0;
    }
    
    @Override
    public boolean perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener, FilePath basePath, String archAllLabel, String sourcePackage) throws IOException, InterruptedException {
        FilePath buildplace = new FilePath(launcher.getChannel(), java.nio.file.Paths.get(workspace.getRemote(), "buildroot").toString());
        FilePath results = new FilePath(launcher.getChannel(), java.nio.file.Paths.get(workspace.getRemote(), "results").toString());
        final Map<String,String> envVars = build.getEnvironment(listener);
        String archFlag = "-b";
        if (!buildplace.exists()) {
            buildplace.mkdirs();
            if (!buildplace.exists()) {
                listener.fatalError("failed to create buildplace dir " + buildplace.getName());
                return false;
            }
        }
        if (!results.exists()) {
            results.mkdirs();
            if (!results.exists()) {
                listener.fatalError("failed to create results dir " + results.getName());
                return false;
            }
        }
        
        EnvVars environment = build.getEnvironment(listener);
        FilePath[] sourcePackageFiles = workspace.list(Util.replaceMacro(sourcePackage, environment));
        if (sourcePackageFiles.length != 1) {
            listener.fatalError("Invalid number of source packages specified (must be 1)");
            return false;
        }
        if(archAllLabel != null)
            if(archAllLabel.startsWith("__SPECIAL__")) {
                if(archAllLabel.equals("__SPECIAL__all_and_arch"))
                    archFlag = "-b";
                else if(archAllLabel.equals("__SPECIAL__arch"))
                    archFlag = "-B";
            }
            else {
                if(envVars.containsValue(archAllLabel))
                    archFlag = "-b";
                else
                    archFlag = "-B";
            }
        ArgumentListBuilder b = new ArgumentListBuilder().add("sudo").add(getTool()).add("--build")
                .add("--buildplace").add(buildplace.toString())
                .add("--buildresult").add(results.toString())
                .add("--basepath").add(basePath.getRemote())
                .add("--debbuildopts").add("\"" + archFlag + "\"")
                .add("--").add(sourcePackageFiles[0]);
        int exitCode = launcher.launch().cmds(b).envs(environment).stdout(listener).stderr(listener.getLogger()).join();
        return exitCode == 0;
    }

    @Override
    public boolean installPackages(Run<?, ?> build, Launcher launcher, TaskListener listener, FilePath basePath, List<String> packages, boolean forceInstall) throws IOException, InterruptedException {
        ArgumentListBuilder b = new ArgumentListBuilder().add("sudo").add(getTool())
                .add("--update")
                .add("--basepath").add(basePath.getRemote())
                .add("--extrapackages")
                .add(StringUtils.join(packages, " "));
        if (forceInstall) {
            b = b.add("--allow-untrusted");
        }
        return launcher.launch().cmds(b).stderr(listener.getLogger()).join() == 0;
    }

    public List<String> getDefaultPackages() {
        return new ImmutableList.Builder<String>()
                .add("software-properties-common")
                .add("python3-software-properties") // fix for ubunt 12.04 to select the fallback packages
                .add("sudo")
                .add("gnupg")
                .add("wget").build();
    }

    // really ugly quickfix for https://github.com/rmohr/chroot-plugin/pull/2
    public List<String> getFallbackPackages() {
        return new ImmutableList.Builder<String>()
                .add("python-software-properties")
                .add("sudo")
                .add("gnupg")
                .add("wget").build();
    }

    @Override
    public boolean addRepositories(FilePath basePath, Launcher launcher, TaskListener log, List<Repository> repositories) throws IOException, InterruptedException {
        if (repositories.size() > 0) {
            String commands = "";
            for (Repository repo : repositories) {
                commands += repo.setUpCommand();
            }
            FilePath script = basePath.getParent().createTextTempFile("chroot", ".sh", commands);

            ArgumentListBuilder cmd = defaultArgumentList(basePath, "--execute")
                    .add("--save-after-exec")
                    .add("--").add(script);
            int ret = launcher.launch().cmds(cmd).stderr(log.getLogger()).join();
            script.delete();
            if (ret != 0) {
                log.fatalError("Could not add custom repositories.");
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean cleanUp(Run<?, ?> build, Launcher launcher, TaskListener listener, FilePath basePath) throws IOException, InterruptedException {
        ArgumentListBuilder a = defaultArgumentList(basePath, "--clean");
        return launcher.launch().cmds(a).stdout(listener).stderr(listener.getLogger()).join() == 0;
    }

    @Override
    public boolean updateRepositories(Run<?, ?> build, Launcher launcher, TaskListener listener, FilePath basePath, FilePath workspace) throws IOException, InterruptedException {
        ArgumentListBuilder b = new ArgumentListBuilder().add("sudo").add(getTool())
                .add("--update")
                .add("--basepath").add(basePath.getRemote());
        return launcher.launch().cmds(b).stderr(listener.getLogger()).join() == 0;
    }

    @Override
    public boolean healthCheck(Launcher launcher) {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ArgumentListBuilder b = new ArgumentListBuilder().add("sudo").add(getTool())
                .add("--help");
        try {
            launcher.launch().cmds(b).stderr(stderr).stdout(stdout).join();
            if (stdout.toString().contains("--basepath")) {
                return true;
            }
        } catch (IOException ex) {
            logger.log(Level.SEVERE, null, ex);
        } catch (InterruptedException ex) {
            logger.log(Level.SEVERE, null, ex);
        }
        logger.log(Level.SEVERE, stderr.toString());
        return false;
    }
}

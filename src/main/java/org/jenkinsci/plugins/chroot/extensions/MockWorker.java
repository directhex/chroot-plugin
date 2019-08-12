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

import com.google.common.collect.ImmutableList;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tools.ToolInstallation;
import hudson.util.ArgumentListBuilder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.chroot.tools.ChrootToolsetProperty;
import org.jenkinsci.plugins.chroot.tools.Repository;
import org.jenkinsci.plugins.chroot.util.ChrootUtil;

/**
 *
 * @author roman
 */
@Extension
public final class MockWorker extends ChrootWorker {

    private static final Logger logger = Logger.getLogger("jenkins.plugins.chroot.extensions.MockWorker");

    @Override
    public FilePath setUp(ToolInstallation tool, Node node, TaskListener log) throws IOException, InterruptedException {
        FilePath rootDir = node.getRootPath();

        // get path to base path
        FilePath basePath;
        ChrootToolsetProperty property = tool.getProperties().get(ChrootToolsetProperty.class);
        // take the property into account if it exists
        String digest = ChrootUtil.getMd5("");
        if (property != null)
            digest = ChrootUtil.getMd5(StringUtils.join(property.getPackagesList(), " "));
        basePath = rootDir.child(getName()).child(tool.getName() + "-" + digest + "." + hudson.model.Executor.currentExecutor().getNumber());
        FilePath cacheDir = basePath.child("cache");
        FilePath buildDir = basePath.child("root");
        FilePath resultDir = basePath.child("result");

        if (!basePath.exists()) {
            // copy /etc/mock/default.cfg to this location
            FilePath system_default_cfg = node.createPath("/etc/mock/" + tool.getName() + ".cfg");
            FilePath system_logging_cfg = node.createPath("/etc/mock/logging.ini");
            FilePath default_cfg = new FilePath(basePath, tool.getName() + "-" + digest + ".cfg");
            FilePath logging_cfg = new FilePath(basePath, "logging.ini");
            FilePath site_default_cfg = new FilePath(basePath, "site-defaults.cfg");

            system_default_cfg.copyTo(default_cfg);
            system_logging_cfg.copyTo(logging_cfg);

            String cfg_content = String.format(
                    "config_opts['basedir'] = '%s'\n"
                    + "config_opts['cache_topdir'] = '%s'\n",
                    buildDir.getRemote(),
                    cacheDir.getRemote());

            site_default_cfg.write(cfg_content, null);
            ArgumentListBuilder cmd = new ArgumentListBuilder();
            cmd.add(getTool())
                    .add("-r").add(default_cfg.getBaseName())
                    .add("--configdir").add(basePath.getRemote())
                    .add("--resultdir").add(resultDir.getRemote())
                    .add("--init");
            Launcher launcher = node.createLauncher(log);
            int ret = launcher.launch().cmds(cmd).stdout(log).stderr(log.getLogger()).join();
        }
        return basePath;
    }

    @Override
    public String getName() {
        return "mock";
    }

    @Override
    public String getTool() {
        return "/usr/bin/mock";
    }

    @Override
    public boolean perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener, FilePath basePath, String commands, String bindMounts, boolean runAsRoot) throws IOException, InterruptedException {
        if (!StringUtils.isEmpty(bindMounts)) {
            logger.log(Level.SEVERE,"Bind mounts not supported by Mock");
            listener.getLogger().append("***Bind mounts not supported by Mock***\n");
            return false;
        }
        String toolName = getToolInstanceName(launcher, listener, basePath);
        String userName = super.getUserName(launcher);
        int id = super.getUID(launcher, userName);
        commands = "cd " + workspace.getRemote() + "\n" + commands;
        FilePath script = workspace.createTextTempFile("chroot", ".sh", commands);

        FilePath rootDir = workspace;
        Node node = basePath.toComputer().getNode();
        FilePath resultDir = rootDir.child("result");
        FilePath buildDir = basePath.child("root");
        FilePath cacheDir = basePath.child("cache");
        FilePath default_cfg = new FilePath(basePath, toolName + ".cfg");

        String cfg_content = String.format(
                "config_opts['basedir'] = '%s'\n"
                + "config_opts['cache_topdir'] = '%s'\n"
                + "config_opts['plugin_conf']['bind_mount_enable'] = True\n"
                + "config_opts['plugin_conf']['bind_mount_opts']['dirs'].append(('%s', '%s' ))\n"
                + "%s", buildDir.getRemote(),
                cacheDir.getRemote(),
                node.getRootPath().absolutize().getRemote(),
                node.getRootPath().absolutize().getRemote(),
                default_cfg.readToString());

        default_cfg.write(cfg_content, "UTF-8");

        ArgumentListBuilder b = new ArgumentListBuilder().add(getTool())
                .add("-r").add(default_cfg.getBaseName())
                .add("--configdir").add(basePath.getRemote())
                .add("--resultdir").add(resultDir.getRemote()).add("--chroot").add("/bin/sh").add(script);

        int exitCode = launcher.launch().cmds(b).stdout(listener).stderr(listener.getLogger()).join();
        script.delete();
        return exitCode == 0;
    }

    @Override
    public boolean perform(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener, FilePath basePath, String archAllLabel, String sourcepackage) throws IOException, InterruptedException {

        EnvVars environment = build.getEnvironment(listener);
        FilePath[] sourcePackageFiles = workspace.list(Util.replaceMacro(sourcepackage, environment));
        if (sourcePackageFiles.length != 1) {
            //log.fatalError("Invalid number of source packages specified (must be 1)");
            return false;
        }

        String toolName = getToolInstanceName(launcher, listener, basePath);
        String userName = super.getUserName(launcher);
        int id = super.getUID(launcher, userName);

        FilePath rootDir = workspace;
        Node node = basePath.toComputer().getNode();
        FilePath resultDir = rootDir.child("result");
        FilePath buildDir = basePath.child("root");
        FilePath cacheDir = basePath.child("cache");
        FilePath default_cfg = new FilePath(basePath, toolName + ".cfg");

        String cfg_content = String.format(
                "config_opts['basedir'] = '%s'\n"
                + "config_opts['cache_topdir'] = '%s'\n"
                + "%s", buildDir.getRemote(),
                cacheDir.getRemote(),
                default_cfg.readToString());

        default_cfg.write(cfg_content, "UTF-8");

        ArgumentListBuilder b = new ArgumentListBuilder().add(getTool())
                .add("-v").add("-r").add(default_cfg.getBaseName())
                .add("--configdir").add(basePath.getRemote())
                .add("--resultdir").add(resultDir.getRemote()).add("--rebuild").add(sourcePackageFiles[0]);

        int exitCode = launcher.launch().cmds(b).stdout(listener).stderr(listener.getLogger()).join();
        return exitCode == 0;
    }

    @Override
    public boolean installPackages(Run<?, ?> build, Launcher launcher, TaskListener listener, FilePath basePath, List<String> packages, boolean forceInstall) throws IOException, InterruptedException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    public List<String> getDefaultPackages() {
        return new ImmutableList.Builder<String>().build();
    }

    @Override
    public boolean addRepositories(FilePath basePath, Launcher launcher, TaskListener log, List<Repository> Repositories) throws IOException, InterruptedException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public boolean cleanUp(Run<?, ?> build, Launcher launcher, TaskListener listener, FilePath basePath) throws IOException, InterruptedException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public boolean updateRepositories(Run<?, ?> build, Launcher launcher, TaskListener log, FilePath basePath) throws IOException, InterruptedException {
        try {
            String toolName = getToolInstanceName(launcher, log, basePath);
            FilePath cacheDir = basePath.child("cache");
            FilePath buildDir = basePath.child("build");
            ArgumentListBuilder cmd = new ArgumentListBuilder();
            FilePath default_cfg = new FilePath(basePath, toolName + ".cfg");
            cmd.add(getTool())
                    .add("-r").add(default_cfg.getBaseName())
                    .add("--configdir").add(basePath.getRemote())
                    .add("--update");
            int ret = launcher.launch().cmds(cmd).stdout(log).stderr(log.getLogger()).join();
            return ret == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean healthCheck(Launcher launcher) {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ArgumentListBuilder b = new ArgumentListBuilder().add(getTool())
                .add("--help");
        try {
            launcher.launch().cmds(b).stderr(stderr).stdout(stdout).join();
            if (stdout.toString().contains("--scm-enable")) {
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

    @Override
    public List<String> getFallbackPackages() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    private static String getToolInstanceName(Launcher launcher, TaskListener log, FilePath basePath) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try {
            ArgumentListBuilder cmd = new ArgumentListBuilder();

            cmd.add("/usr/bin/basename").add(basePath.toString()).add("." + hudson.model.Executor.currentExecutor().getNumber());
            int ret = launcher.launch().cmds(cmd).stdout(stdout).stderr(log.getLogger()).join();
        } catch (Exception e) {
            return null;
        }
        return stdout.toString().trim();
    }
}

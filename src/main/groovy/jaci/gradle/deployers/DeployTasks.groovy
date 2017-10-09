package jaci.gradle.deployers

import jaci.gradle.EmbeddedTools
import jaci.gradle.targets.*

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.platform.base.*
import org.gradle.nativeplatform.*

import org.hidetake.groovy.ssh.Ssh
import org.hidetake.groovy.ssh.core.*
import org.hidetake.groovy.ssh.core.settings.*
import org.hidetake.groovy.ssh.connection.*

import java.security.MessageDigest
import java.nio.file.Files

class DeployerTask extends DefaultTask {
    @Input
    Deployer deployer

    @TaskAction
    void configureDeploy() {
        ext.deployer = deployer
    }
}

class ConfigureNativeBinaryTask extends DefaultTask {
    @Input
    File file

    @Input
    NativeBinarySpec spec

    @TaskAction
    void configureBinary() {
        ext.file = file
        ext.spec = spec
    }
}

class DetermineTargetAddressTask extends DefaultTask {
    @Input
    RemoteTarget target

    @Input
    int targetid

    @TaskAction
    void determineAddress() {
        if (project.hasProperty("deploy-dry")) {
            println "-> DRY RUN: Using target address ${target.addresses[0]}"
        } else {
            ext.password = target.password == null ? "" : target.password
            if (target.promptPassword) {
                def pass = EmbeddedTools.promptPassword(target.user)
                if (pass != null) ext.password = pass
            }

            ext.foundTarget = false
            if (target.asyncFind) {
                def found = []
                
                EmbeddedTools.silenceSsh()
                try {
                    EmbeddedTools.ssh.run {
                        target.addresses.forEach { addr ->
                            println "-> Attempting target address: ${addr}"
                            session(host: addr, user: target.user, password: ext.password, timeoutSec: target.timeout, knownHosts: AllowAnyHosts.instance) {
                                println "-> Target ${addr} found!"
                                found << addr
                            }
                        }
                    }
                } catch (all) {}
                EmbeddedTools.unsilenceSsh()

                ext.foundTarget = found.size() > 0
                if (ext.foundTarget) {
                    ext.address = found.last()
                }
            } else {
                target.addresses.any { addr ->
                    println "-> Attempting target address: ${addr}"
                    EmbeddedTools.silenceSsh()
                    try {
                        EmbeddedTools.ssh.run {
                            session(host: addr, user: target.user, password: ext.password, timeoutSec: target.timeout, knownHosts: AllowAnyHosts.instance) {
                                println "-> Target ${addr} found!"
                                ext.address = addr
                                ext.foundTarget = true
                            }
                        }
                    } catch (all) {}
                    EmbeddedTools.unsilenceSsh()
                    return ext.foundTarget
                }
            }
            if (!ext.foundTarget) {
                if (target.failOnMissing) 
                    throw new DeployException("Target ${target.name} could not be found! Failing as ${target.name}.failOnMissing is true.")
                else
                    println "Target ${target.name} could not be found! Ignoring as ${target.name}.failOnMissing is false."
            }
        }
    }
}

class DeployTargetTask extends DefaultTask {
    @Input
    RemoteTarget target

    @Input
    String addrTaskName

    @TaskAction
    void deploy() {
        def dryrun = project.hasProperty("deploy-dry")
        def skipcache = project.hasProperty("deploy-dirty")

        def printmsg = project.hasProperty("deploy-quiet") ? { msg -> } : { msg -> println msg }

        def rootDirectory = target.directory == null ? "." : target.directory

        def deployerList = []
        // Find all deployers that will be bound to this target
        project.tasks.findAll { this in it.finalizedBy.getDependencies() }.forEach { deployerTask -> 
            deployerList << deployerTask.ext.deployer
        }
        deployerList = deployerList.toSorted { a, b -> a.getOrder() <=> b.getOrder() }

        deployerList.forEach { deployer -> 
            if (dryrun) {
                runDeploy(deployer, rootDirectory, { cmd, workingdir ->
                    printmsg "-C-> ${cmd} @ ${workingdir}"
                }, { filesrc, filedst, workingdir, cache ->
                    printmsg "-F-> (${cache && !skipcache ? 'CACHE' : 'NO CACHE'}) ${filesrc} ==> ${filedst}"
                })
            } else {
                def addrTask = project.tasks.findByName(addrTaskName)
                if (!addrTask.foundTarget) {
                    println "-> Skipping target ${target.name} (could not find target)"
                    return
                }
                def address = addrTask.address
                def user = target.user
                def password = addrTask.password

                if (deployer.user != null) {
                    user = deployer.user
                    password = deployer.password
                    if (deployer.promptPassword) {
                        def pass = EmbeddedTools.promptPassword(user)
                        if (pass != null) password = pass
                    }
                }

                EmbeddedTools.ssh.run {
                    session(host: address, user: user, password: password, timeoutSec: target.timeout, knownHosts: AllowAnyHosts.instance) {
                        def cachecheck = !skipcache
                        def skip_md5_cmd = false
                        try {
                            def sum = execute "echo test | md5sum"
                            if (!sum.split(" ")[0].equalsIgnoreCase("d8e8fca2dc0f896fd7cb4cb0031ba249")) {
                                printmsg "-> md5sum not producing expected output on remote target. Skipping Cache Check for MD5_CMD artifacts..."
                                skip_md5_cmd = true
                            }
                        } catch (all) {
                            printmsg "-> md5sum not supported on remote target. Skipping Cache Check for MD5_CMD artifacts..."
                            skip_md5_cmd = true
                        }

                        runDeploy(deployer, rootDirectory, { cmd, workingdir ->
                            if (target.mkdirs) execute "mkdir -p ${workingdir}"
                            
                            printmsg "-C-> ${cmd} @ ${workingdir}"
                            def result = execute([ "cd ${workingdir}", cmd ].join('\n'))
                            printmsg "------> ${result}"

                        }, { filesrc, filedst, workingdir, cache, cache_method ->
                            if (target.mkdirs) execute "mkdir -p ${workingdir}"

                            def toDeploy = true
                            def md5LocalSum = null
                            if (cache && cachecheck) {
                                if ((!skip_md5_cmd && cache_method == CacheMethod.MD5_CMD) || cache_method == CacheMethod.MD5_FILE) {
                                    def remote_md5 = ""
                                    if (cache_method == CacheMethod.MD5_CMD) {
                                        remote_md5 = execute("md5sum ${filedst} 2> /dev/null || true").split(" ")[0]
                                    } else {
                                        remote_md5 = execute("cat ${filedst}.md5 2> /dev/null || true")
                                    }
                                    def md = MessageDigest.getInstance("MD5")
                                    md.update(Files.readAllBytes(filesrc.toPath()))
                                    def local_md5 = md.digest().encodeHex().toString()
                                    if (cache_method == CacheMethod.MD5_FILE) {
                                        md5LocalSum = local_md5
                                    }

                                    if (remote_md5.equals(local_md5)) toDeploy = false
                                } else if (cache_method == CacheMethod.EXISTS) {
                                    def remote_exists = execute("[ -f '${filedst}' ] && echo 'Found'")
                                    toDeploy = !remote_exists.equals('Found')
                                }
                            }

                            if (toDeploy) {
                                printmsg "-F->${cache && cachecheck ? " (OUT OF DATE)" : ""} ${filedst}"
                                put from: filesrc, into: filedst
                                if (md5LocalSum != null) {
                                    execute "echo ${md5LocalSum} > ${filedst}.md5"
                                }
                            }
                        })
                    }
                }
            }
        }
    }

    void runDeploy(deployer, rootDirectory, cmd_callback, file_callback) {
        deployer.getPredeploy().forEach { cmd ->
            cmd_callback(cmd, rootDirectory)
        } 

        deployer.getArtifacts().toSorted { a, b -> a.getOrder() <=> b.getOrder() }.forEach { artifact ->
            def deployDirectory = (artifact.directory != null) ? EmbeddedTools.join(rootDirectory, artifact.directory) : rootDirectory

            if (artifact.getPredeploy() != null) artifact.getPredeploy().forEach { cmd ->
                cmd_callback(cmd, deployDirectory)
            }

            def customFilename = (artifact instanceof FileArtifact) ? artifact.getFilename() : null
            deployDirectory = EmbeddedTools.normalize(deployDirectory)
            def deploySourceFile = null
            def deployTargetFile = customFilename == null ? null : EmbeddedTools.join(deployDirectory, customFilename)

            if (artifact instanceof JavaArtifact) {
                def task = project.tasks.findByName(artifact.name)
                if (task != null) {
                    def files = task.outputs.files.files
                    if (files != null && !files.isEmpty()) {
                        def file = files[0] 
                        def filename = customFilename == null ? file.name : customFilename
                        deploySourceFile = file
                        deployTargetFile = (deployTargetFile == null) ? EmbeddedTools.join(deployDirectory, filename) : deployTargetFile
                    }
                }
            } else if (artifact instanceof NativeArtifact) {
                def taskname = "configureDeployArtifact${deployer.name.capitalize()}${artifact.name.capitalize()}"
                def task = project.tasks.findByName(taskname)
                if (task != null) {
                    def file = task.file
                    def filename = customFilename == null ? file.name : customFilename
                    deploySourceFile = file
                    deployTargetFile = (deployTargetFile == null) ? EmbeddedTools.join(deployDirectory, filename) : deployTargetFile

                    if (artifact.libraries) {
                        def libDeployDir = artifact.librootdir == null ? deployDirectory : EmbeddedTools.normalize(EmbeddedTools.join(deployDirectory, artifact.librootdir))
                        task.spec.getLibs().forEach { lib -> 
                            lib.getRuntimeFiles().files.forEach { libfile ->
                                if (libfile.exists()) {
                                    def libTargetFile = EmbeddedTools.join(libDeployDir, libfile.name)
                                    file_callback(libfile, libTargetFile, libDeployDir, artifact.librarycache, artifact.cacheMethod)
                                }
                            }
                        }
                    }
                }
            } else if (artifact instanceof FileArtifact) {
                def file = artifact.getFile()
                def filename = customFilename == null ? file.name : customFilename
                deploySourceFile = file
                deployTargetFile = (deployTargetFile == null) ? EmbeddedTools.join(deployDirectory, filename) : deployTargetFile
            } else if (artifact instanceof FileSetArtifact) {
                def files = artifact.getFiles()
                files.forEach { file ->
                    def targetFile = EmbeddedTools.join(deployDirectory, file.name)
                    file_callback(file, targetFile, deployDirectory, artifact.cache, artifact.cacheMethod)
                }
            } else if (artifact instanceof CommandArtifact) {
                cmd_callback(artifact.getCommand(), deployDirectory)
            }
            
            if (deploySourceFile != null && deployTargetFile != null) {
                deployTargetFile = EmbeddedTools.normalize(deployTargetFile)
                if (deploySourceFile.exists())
                file_callback(deploySourceFile, deployTargetFile, deployDirectory, artifact.cache, artifact.cacheMethod)
            }

            if (artifact.getPostdeploy() != null) artifact.getPostdeploy().forEach { cmd ->
                cmd_callback(cmd, deployDirectory)
            }
        }

        deployer.getPostdeploy().forEach { cmd -> 
            cmd_callback(cmd, rootDirectory)
        }
    }
}
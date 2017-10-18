package jaci.gradle.deploy

import groovy.transform.CompileStatic
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.model.ModelMap
import org.gradle.model.Mutate
import org.gradle.model.RuleSource
import org.gradle.nativeplatform.Repositories
import org.gradle.platform.base.BinaryContainer

@CompileStatic
class DeployPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        def deployExt = new DeployExtension(project)
        project.extensions.add('deploy', deployExt)
    }

    static class DeployRules extends RuleSource {
        @Mutate
        void createBinariesTasks(final ModelMap<Task> tasks, final Repositories repos, final ExtensionContainer ext, final BinaryContainer binaries) {
//            ext.getByType(DeployExtension).deployers.each { Deployer deployer ->
//                deployer.artifacts.each { ArtifactBase artifact ->
//                    if (artifact instanceof NativeArtifact) {
//                        NativeArtifact na = artifact as NativeArtifact
//                        binaries.each { bin ->
//                            if (bin instanceof NativeBinarySpec) {
//                                NativeBinarySpec spec = bin as NativeBinarySpec
//
//                                if (spec.component.name == na.component && spec.targetPlatform.name == na.targetPlatform) {
//                                    spec.tasks.withType(AbstractLinkTask) { AbstractLinkTask task ->
//                                        na.linkOut = task.outputs
//                                    }
//                                }
//                            }
//                        }
//                    } else if (artifact instanceof NativeLibraryArtifact) {
//                        NativeLibraryArtifact nla = artifact as NativeLibraryArtifact
//                        repos.withType(PrebuiltLibraries).all { PrebuiltLibraries repo ->
//                            repo.matching { PrebuiltLibrary pl -> pl.name == nla.library }.all { PrebuiltLibrary pl ->
//                                pl.binaries.all { NativeLibraryBinary bin ->
//                                    FileCollection sharedLibs = (bin instanceof NativeLibBinary) ? (bin as NativeLibBinary).runtimeLibraries : bin.runtimeFiles
//                                    FileTree deployedFileTree = sharedLibs.asFileTree
//                                    if (nla.matchers != null && !nla.matchers.empty) {
//                                        deployedFileTree = deployedFileTree.matching { PatternFilterable pat -> pat.include(nla.matchers) }
//                                    }
//
//                                    nla.files = deployedFileTree
//                                }
//                            }
//                        }
//                    }
//                }
//            }
        }
    }
}

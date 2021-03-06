package jaci.gradle.deploy.cache

import groovy.transform.CompileStatic
import jaci.gradle.deploy.context.DeployContext

@CompileStatic
@FunctionalInterface
interface CacheCheckerFunction {
  boolean check(DeployContext ctx, String filename, File localFile)
}

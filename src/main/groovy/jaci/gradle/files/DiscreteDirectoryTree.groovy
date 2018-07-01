package jaci.gradle.files

import groovy.transform.CompileStatic

@CompileStatic
class DiscreteDirectoryTree extends AbstractDirectoryTree {

    Set<File> set

    DiscreteDirectoryTree() {
        set = []
    }

    void add(File f) {
        set.add(f)
    }

    @Override
    Set<File> getDirectories() {
        return set
    }

}
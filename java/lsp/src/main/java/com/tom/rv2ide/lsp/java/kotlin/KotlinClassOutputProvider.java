/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.java.kotlin;

import com.tom.rv2ide.projects.ModuleProject;
import com.tom.rv2ide.projects.android.AndroidModule;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/** Locates existing Kotlin JVM class directories for the selected Android variant. */
public final class KotlinClassOutputProvider {

  private KotlinClassOutputProvider() {}

  /**
   * Returns only materialized outputs. This method never creates directories and never invokes
   * Gradle/Kotlin compilation.
   */
  public static Set<File> findCompileOutputs(ModuleProject module) {
    final Set<File> outputs = new LinkedHashSet<>();
    if (module == null) {
      return outputs;
    }
    addModuleOutputs(module, outputs);
    for (ModuleProject dependency : module.getCompileModuleProjects()) {
      addModuleOutputs(dependency, outputs);
    }
    return outputs;
  }

  private static void addModuleOutputs(ModuleProject module, Set<File> outputs) {
    if (!(module instanceof AndroidModule)) {
      return;
    }
    final AndroidModule androidModule = (AndroidModule) module;
    final com.tom.rv2ide.tooling.api.models.BasicAndroidVariantMetadata variant =
        androidModule.getSelectedVariant();
    if (variant == null) {
      return;
    }
    final String variantName = variant.getName();
    if (variantName == null || variantName.isEmpty()) {
      return;
    }
    final File buildDir = androidModule.getBuildDir();
    addIfDirectory(outputs, new File(buildDir, "tmp/kotlin-classes/" + variantName));
    addIfDirectory(outputs, new File(buildDir, "tmp/kapt3/classes/" + variantName));
  }

  private static void addIfDirectory(Set<File> outputs, File candidate) {
    if (candidate.isDirectory()) {
      outputs.add(candidate);
    }
  }
}
package com.tom.rv2ide.tooling.impl.internal

import com.tom.rv2ide.builder.model.IJavaCompilerSettings
import com.tom.rv2ide.tooling.api.IJavaProject
import com.tom.rv2ide.tooling.api.ProjectType
import com.tom.rv2ide.tooling.api.models.JavaContentRoot
import com.tom.rv2ide.tooling.api.models.JavaModuleDependency
import com.tom.rv2ide.tooling.api.models.JavaProjectMetadata
import java.io.File
import java.io.Serializable
import java.util.concurrent.CompletableFuture

internal class CompositeBuildJavaProjectImpl(
  private val metadata: JavaProjectMetadata,
  private val contentRoots: List<JavaContentRoot>,
  private val dependencies: List<JavaModuleDependency>,
) : IJavaProject, Serializable {

  private val serialVersionUID = 1L

  override fun getMetadata(): CompletableFuture<com.tom.rv2ide.tooling.api.models.ProjectMetadata> {
    return CompletableFuture.completedFuture(metadata)
  }

  override fun getContentRoots(): CompletableFuture<List<JavaContentRoot>> {
    return CompletableFuture.completedFuture(contentRoots)
  }

  override fun getDependencies(): CompletableFuture<List<JavaModuleDependency>> {
    return CompletableFuture.completedFuture(dependencies)
  }

  override fun getClasspaths(): CompletableFuture<List<File>> {
    return CompletableFuture.completedFuture(
      dependencies.mapNotNull { it.jarFile }.toMutableList().apply {
        metadata.classesJar?.let { add(it) }
      }
    )
  }
}
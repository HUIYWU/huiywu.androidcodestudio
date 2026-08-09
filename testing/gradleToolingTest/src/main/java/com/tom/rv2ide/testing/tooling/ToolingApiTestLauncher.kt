/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.testing.tooling

import com.tom.rv2ide.tooling.api.IToolingApiClient
import com.tom.rv2ide.tooling.api.IToolingApiServer
import com.tom.rv2ide.tooling.api.messages.GradleDistributionParams
import com.tom.rv2ide.tooling.api.messages.InitializeProjectParams
import com.tom.rv2ide.tooling.api.messages.LogMessageParams
import com.tom.rv2ide.tooling.api.messages.result.BuildResult
import com.tom.rv2ide.tooling.api.messages.result.GradleWrapperCheckResult
import com.tom.rv2ide.tooling.api.util.ToolingApiLauncher
import com.tom.rv2ide.tooling.events.ProgressEvent
import com.tom.rv2ide.utils.FileProvider
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * Starts the already-built Tooling API fat jar for integration tests.
 *
 * The launcher deliberately uses each fixture's Gradle wrapper. This keeps the tested project
 * self-contained and avoids legacy AGP/Gradle version rewriting or an implicit host installation.
 */
object ToolingApiTestLauncher {

  @JvmStatic
  fun launch(
      projectDir: Path = FileProvider.testProjectRoot(),
      action: ToolingApiTestScope.() -> Unit,
  ) {
    val jar = FileProvider.implModule().resolve("build/libs/tooling-api-all.jar").toFile()
    check(jar.isFile) {
      "Missing tooling API test jar at ${jar.absolutePath}; build :tooling:impl:jar before running integration tests."
    }

    val process = ProcessBuilder(javaCommand(), "-jar", jar.absolutePath).start()
    val stderrReader = Thread(StreamLogger(process.errorStream), "ToolingApiTestLauncher-stderr").also {
      it.isDaemon = true
      it.start()
    }
    val client = LoggingClient()
    val launcher = ToolingApiLauncher.newClientLauncher(client, process.inputStream, process.outputStream)
    launcher.startListening()
    val server = launcher.remoteProxy as IToolingApiServer
    // IProject is registered as a remote interface by ToolingApiLauncher; retain this proxy instead
    // of asking the server to serialize its concrete ProjectImpl back over JSON-RPC.
    val project = launcher.remoteProxy as com.tom.rv2ide.tooling.api.IProject

    try {
      val result =
          server.initialize(
                  InitializeProjectParams(
                      directory = projectDir.toAbsolutePath().normalize().toString(),
                      gradleDistribution = GradleDistributionParams.WRAPPER,
                  )
              )
              .get()
      check(result.isSuccessful) { "Tooling initialization failed: $result" }
      ToolingApiTestScope(server, project, result).action()
    } finally {
      runCatching { server.cancelCurrentBuild().get() }
      runCatching { server.shutdown().get() }
      process.destroy()
      stderrReader.join(1_000L)
    }
  }

  private fun javaCommand(): String {
    val javaHome = System.getenv("JAVA_HOME")?.takeIf(String::isNotBlank)?.let(::File)
    val candidate = javaHome?.resolve("bin/java")
    return candidate?.takeIf { it.isFile && it.canExecute() }?.absolutePath ?: "java"
  }

  private class LoggingClient : IToolingApiClient {
    override fun logMessage(params: LogMessageParams) = Unit
    override fun logOutput(line: String) = Unit
    override fun prepareBuild(buildInfo: com.tom.rv2ide.tooling.api.messages.result.BuildInfo) = Unit
    override fun onBuildSuccessful(result: BuildResult) = Unit
    override fun onBuildFailed(result: BuildResult) = Unit
    override fun onProgressEvent(event: ProgressEvent) = Unit
    override fun getBuildArguments(): CompletableFuture<List<String>> =
        CompletableFuture.completedFuture(listOf("--stacktrace"))
    override fun checkGradleWrapperAvailability(): CompletableFuture<GradleWrapperCheckResult> =
        CompletableFuture.completedFuture(GradleWrapperCheckResult(true))
  }

  private class StreamLogger(private val input: InputStream) : Runnable {
    override fun run() {
      InputStreamReader(input).buffered().useLines { lines -> lines.forEach { System.err.println(it) } }
    }
  }
}

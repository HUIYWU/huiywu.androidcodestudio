package com.tom.rv2ide.common.logging

object IdeLogConfig {
  @Volatile
  private var ideLogsEnabledProvider: (() -> Boolean)? = null

  @Volatile
  private var ideDebugLogsEnabledProvider: (() -> Boolean)? = null

  @JvmStatic
  fun setIdeLogsEnabledProvider(provider: (() -> Boolean)?) {
    ideLogsEnabledProvider = provider
  }

  @JvmStatic
  fun setIdeDebugLogsEnabledProvider(provider: (() -> Boolean)?) {
    ideDebugLogsEnabledProvider = provider
  }

  @JvmStatic
  fun shouldLogIde(): Boolean {
    return shouldLogInfo()
  }

  @JvmStatic
  fun shouldLogInfo(): Boolean {
    return ideLogsEnabledProvider?.invoke() == true
  }

  @JvmStatic
  fun shouldLogWarn(): Boolean {
    return ideLogsEnabledProvider?.invoke() == true
  }

  @JvmStatic
  fun shouldLogError(): Boolean {
    return ideLogsEnabledProvider?.invoke() == true
  }

  @JvmStatic
  fun shouldLogDebug(): Boolean {
    return ideLogsEnabledProvider?.invoke() == true && ideDebugLogsEnabledProvider?.invoke() == true
  }

  @JvmStatic
  fun shouldLogTrace(): Boolean {
    return shouldLogDebug()
  }
}

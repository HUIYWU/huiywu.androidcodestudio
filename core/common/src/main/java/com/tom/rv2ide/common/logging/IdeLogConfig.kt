package com.tom.rv2ide.common.logging

object IdeLogConfig {
  @Volatile
  private var ideLogsEnabledProvider: (() -> Boolean)? = null

  @JvmStatic
  fun setIdeLogsEnabledProvider(provider: (() -> Boolean)?) {
    ideLogsEnabledProvider = provider
  }

  @JvmStatic
  fun shouldLogIde(): Boolean {
    return ideLogsEnabledProvider?.invoke() == true
  }
}

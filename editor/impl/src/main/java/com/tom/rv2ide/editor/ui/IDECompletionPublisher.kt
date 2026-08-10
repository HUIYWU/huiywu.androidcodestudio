/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.editor.ui

import android.os.Handler
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * [CompletionPublisher] implementation for AndroidIDE.
 *
 * @author Akash Yadav
 */
class IDECompletionPublisher(
    private val handler: Handler,
    private val updateCallback: Runnable,
    languageInterruptionLevel: Int,
) : CompletionPublisher(handler, updateCallback, languageInterruptionLevel) {

  companion object {
    private val generationCounter = AtomicLong()
  }

  val generation: Long = generationCounter.incrementAndGet()

  @Volatile private var cancelled = false
  @Volatile private var awaitingDeferredResult = false
  private val cancelListeners = CopyOnWriteArrayList<() -> Unit>()

  init {
    setUpdateThreshold(1)
  }

  override fun cancel() {
    if (cancelled) {
      return
    }
    cancelled = true
    super.cancel()
    cancelListeners.forEach { it.invoke() }
    cancelListeners.clear()
  }

  fun isCancelled(): Boolean = cancelled

  /** Invoked at most once when Sora cancels this publisher's CompletionThread. */
  fun onCancelled(listener: () -> Unit) {
    if (cancelled) {
      listener()
      return
    }
    cancelListeners.add(listener)
    // Cancellation may race the add above.
    if (cancelled && cancelListeners.remove(listener)) {
      listener()
    }
  }

  /**
   * Keeps Sora 0.23.7's CompletionThread from treating a subscribed deferred request as a normal
   * empty result and immediately hiding the completion window.
   */
  fun awaitDeferredResult() {
    awaitingDeferredResult = true
  }

  /**
   * Consumes the deferred marker only while no real candidates have arrived.
   *
   * A worker may finish before Sora 0.23.7 runs CompletionThread's initial callback. In that case
   * the callback must render the already-added items rather than consume their update as the
   * placeholder refresh.
   */
  fun consumeDeferredResultMarker(): Boolean {
    if (!awaitingDeferredResult || items.isNotEmpty()) {
      awaitingDeferredResult = false
      return false
    }
    awaitingDeferredResult = false
    return true
  }

  /** Clears the marker when a deferred worker cannot provide a result. */
  fun completeDeferredResult() {
    awaitingDeferredResult = false
  }

  /**
   * Publishes a successful empty deferred result through the same guarded UI callback as items.
   *
   * Sora does not schedule an update for [addLSPItems] with an empty collection. Without this
   * explicit callback, a current request that correctly has no candidates can leave the prior
   * generation's list visible after its placeholder callback was consumed.
   */
  fun publishEmptyDeferredResult() {
    awaitingDeferredResult = false
    handler.post(updateCallback)
  }

  override fun hasData(): Boolean = awaitingDeferredResult || super.hasData()

  /** Adds the given [completion items][items] to the completion list. */
  fun <CompletionItemT : CompletionItem> addLSPItems(items: Collection<CompletionItemT>) {
    super.addItems(items)
  }
}


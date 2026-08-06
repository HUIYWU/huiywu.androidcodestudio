/*
 * This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.projects.internal

import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.xml.resources.ResourceTableInputSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test

class ResourceTableRefreshCoordinatorTest {

  @Test
  fun coalescesRapidChangesPerModule() {
    val coordinator = ResourceTableRefreshCoordinator(debounceMillis = 80L)
    val refreshCount = AtomicInteger()
    val snapshotCount = AtomicInteger()
    try {
      repeat(5) {
        coordinator.scheduleForTest(
            key = ":app",
            snapshot = {
              snapshotCount.incrementAndGet()
              ResourceTableInputSnapshot.EMPTY
            },
            publish = { _, _ -> refreshCount.incrementAndGet() },
        )
        Thread.sleep(10L)
      }

      awaitValue(refreshCount, 1)
      Thread.sleep(100L)
      assertThat(refreshCount.get()).isEqualTo(1)
      assertThat(snapshotCount.get()).isEqualTo(1)
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun immediateRefreshRunsWithoutDebounce() {
    val coordinator = ResourceTableRefreshCoordinator(debounceMillis = 5_000L)
    val completed = CountDownLatch(1)
    try {
      coordinator.scheduleForTest(
          key = ":app",
          immediate = true,
          snapshot = { ResourceTableInputSnapshot.EMPTY },
          publish = { _, _ -> completed.countDown() },
      )

      assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue()
    } finally {
      coordinator.close()
    }
  }

  @Test
  fun newerRequestMakesRunningRequestObsolete() {
    val coordinator = ResourceTableRefreshCoordinator(debounceMillis = 0L)
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val obsoleteAtEnd = AtomicReference<Boolean>()
    val refreshCount = AtomicInteger()
    try {
      coordinator.scheduleForTest(
          key = ":app",
          immediate = true,
          snapshot = { ResourceTableInputSnapshot.EMPTY },
          publish = { _, isObsolete ->
            refreshCount.incrementAndGet()
            firstStarted.countDown()
            releaseFirst.await(1, TimeUnit.SECONDS)
            obsoleteAtEnd.set(isObsolete())
          },
      )
      assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue()

      coordinator.scheduleForTest(
          key = ":app",
        immediate = true,
        snapshot = { ResourceTableInputSnapshot.EMPTY },
        publish = { _, _ -> refreshCount.incrementAndGet() },
      )
      releaseFirst.countDown()

      awaitValue(refreshCount, 2)
      assertThat(obsoleteAtEnd.get()).isTrue()
    } finally {
      releaseFirst.countDown()
      coordinator.close()
    }
  }

  @Test
  fun closeCancelsPendingDebouncedRefresh() {
    val coordinator = ResourceTableRefreshCoordinator(debounceMillis = 500L)
    val refreshCount = AtomicInteger()
    coordinator.scheduleForTest(
        key = ":app",
        snapshot = { ResourceTableInputSnapshot.EMPTY },
        publish = { _, _ -> refreshCount.incrementAndGet() },
    )

    coordinator.close()
    Thread.sleep(650L)

    assertThat(refreshCount.get()).isEqualTo(0)
  }

  private fun awaitValue(value: AtomicInteger, expected: Int) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
    while (value.get() != expected && System.nanoTime() < deadline) {
      Thread.sleep(10L)
    }
    assertThat(value.get()).isEqualTo(expected)
  }
}
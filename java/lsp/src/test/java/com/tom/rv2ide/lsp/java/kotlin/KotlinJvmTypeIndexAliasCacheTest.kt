package com.tom.rv2ide.lsp.java.kotlin

import java.nio.file.Paths
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Lifecycle coverage for the per-consumer alias visibility cache. */
class KotlinJvmTypeIndexAliasCacheTest {

  @Test
  fun consumerAliasCache_hitsForSamePathAndSource() {
    val cache = KotlinJvmTypeIndex.ConsumerAliasCache<String>()
    val file = Paths.get("/consumer/Api.kt")
    val aliases = Collections.singletonMap("Name", "String")

    cache.put(file, "package consumer", aliases)

    assertSame(aliases, cache.get(file, "package consumer"))
    assertEquals(1, cache.size())
  }

  @Test
  fun consumerAliasCache_replacesOldConsumerContentWithoutGrowing() {
    val cache = KotlinJvmTypeIndex.ConsumerAliasCache<String>()
    val file = Paths.get("/consumer/Api.kt")
    val oldAliases = Collections.singletonMap("OldName", "String")
    val newAliases = Collections.singletonMap("NewName", "Int")

    cache.put(file, "import shared.OldName", oldAliases)
    assertNull(cache.get(file, "import shared.NewName"))
    cache.put(file, "import shared.NewName", newAliases)

    assertNull(cache.get(file, "import shared.OldName"))
    assertSame(newAliases, cache.get(file, "import shared.NewName"))
    assertEquals(1, cache.size())
  }

  @Test
  fun consumerAliasCache_normalizesEquivalentPaths() {
    val cache = KotlinJvmTypeIndex.ConsumerAliasCache<String>()
    val aliases = Collections.singletonMap("Name", "String")

    cache.put(Paths.get("/consumer/dir/../Api.kt"), "source", aliases)

    assertSame(aliases, cache.get(Paths.get("/consumer/Api.kt"), "source"))
    assertEquals(1, cache.size())
  }
}
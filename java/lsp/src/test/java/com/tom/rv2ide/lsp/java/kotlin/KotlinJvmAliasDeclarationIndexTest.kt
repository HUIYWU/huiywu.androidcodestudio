package com.tom.rv2ide.lsp.java.kotlin

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coverage for the immutable per-revision alias declaration snapshot. */
class KotlinJvmAliasDeclarationIndexTest {

  @Test
  fun declarationIndex_filtersDirectAndGenericAliasesWithoutRescanningRoots() {
    val root = Files.createTempDirectory("kotlin-alias-declaration-index")
    try {
      val shared = root.resolve("shared").also { Files.createDirectories(it) }
      val consumer = root.resolve("consumer").also { Files.createDirectories(it) }
      write(shared.resolve("Aliases.kt"), """
        package shared
        typealias ImportedName = String
        typealias ImportedList<T> = List<T>
        private typealias HiddenName = Int
        typealias NullableList<T> = List<T>?
      """.trimIndent())
      write(consumer.resolve("Local.kt"), """
        package consumer
        typealias LocalName = Long
        typealias LocalSet<T> = Set<T>
      """.trimIndent())
      val consumerFile = consumer.resolve("Use.kt")
      val consumerSource = """
        package consumer
        import shared.ImportedName
        import shared.ImportedList
      """.trimIndent()
      write(consumerFile, consumerSource)

      val index = KotlinJvmTypeIndex.AliasDeclarationIndex.build(listOf(root.toFile()))
      val direct = index.visibleDirectAliases(consumerFile, consumerSource)
      val generic = index.visibleGenericAliases(consumerFile, consumerSource)

      assertEquals("String", direct["ImportedName"])
      assertEquals("Long", direct["LocalName"])
      assertTrue(generic.containsKey("ImportedList"))
      assertTrue(generic.containsKey("LocalSet"))
      assertFalse(direct.containsKey("HiddenName"))
      assertFalse(generic.containsKey("NullableList"))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun declarationIndex_isImmutableUntilTheNextRevisionSnapshotIsBuilt() {
    val root = Files.createTempDirectory("kotlin-alias-declaration-revision")
    try {
      val shared = root.resolve("shared").also { Files.createDirectories(it) }
      val consumer = root.resolve("consumer").also { Files.createDirectories(it) }
      val aliasFile = shared.resolve("Aliases.kt")
      val consumerFile = consumer.resolve("Use.kt")
      val consumerSource = """
        package consumer
        import shared.ApiName
      """.trimIndent()
      write(aliasFile, "package shared\ntypealias ApiName = String")
      write(consumerFile, consumerSource)

      val revisionOne = KotlinJvmTypeIndex.AliasDeclarationIndex.build(listOf(root.toFile()))
      write(aliasFile, "package shared\ntypealias ApiName = Long")

      // A published snapshot must not observe later file edits. Module revision invalidation is
      // responsible for selecting a newly built snapshot.
      assertEquals("String", revisionOne.visibleDirectAliases(consumerFile, consumerSource)["ApiName"])

      val revisionTwo = KotlinJvmTypeIndex.AliasDeclarationIndex.build(listOf(root.toFile()))
      assertEquals("Long", revisionTwo.visibleDirectAliases(consumerFile, consumerSource)["ApiName"])
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun navigationCandidateIndex_reusesImmutableDeclarationAndMultifileMetadata() {
    val root = Files.createTempDirectory("kotlin-navigation-candidates")
    try {
      val regular = root.resolve("Regular.kt")
      val firstPart = root.resolve("FirstPart.kt")
      val secondPart = root.resolve("SecondPart.kt")
      write(regular, "package sample\nclass Regular")
      write(firstPart, """
        @file:JvmName("SharedFacade")
        @file:JvmMultifileClass
        package sample
        fun first() = 1
      """.trimIndent())
      write(secondPart, """
        @file:JvmName("SharedFacade")
        @file:JvmMultifileClass
        package sample
        fun second() = 2
      """.trimIndent())

      val index = KotlinJvmTypeIndex.NavigationCandidateIndex.build(listOf(root.toFile()))

      assertEquals("Regular.kt", index.declaration("sample.Regular")!!.file.fileName.toString())
      assertEquals(2, index.multifileDeclarations("sample.SharedFacade").size)
      write(regular, "package sample\nclass Changed")
      assertEquals("Regular.kt", index.declaration("sample.Regular")!!.file.fileName.toString())
      assertNull(index.declaration("sample.Changed"))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun declarationIndex_rejectsSameSimpleNameConflicts() {
    val root = Files.createTempDirectory("kotlin-alias-declaration-conflict")
    try {
      val first = root.resolve("first").also { Files.createDirectories(it) }
      val second = root.resolve("second").also { Files.createDirectories(it) }
      val consumer = root.resolve("consumer").also { Files.createDirectories(it) }
      write(first.resolve("Aliases.kt"), "package first\ntypealias Shared = String")
      write(second.resolve("Aliases.kt"), "package second\ntypealias Shared = Int")
      val consumerFile = consumer.resolve("Use.kt")
      val consumerSource = """
        package consumer
        import first.Shared
        import second.Shared
      """.trimIndent()
      write(consumerFile, consumerSource)

      val index = KotlinJvmTypeIndex.AliasDeclarationIndex.build(listOf(root.toFile()))

      assertFalse(index.visibleDirectAliases(consumerFile, consumerSource).containsKey("Shared"))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun write(path: Path, source: String) {
    Files.write(path, source.toByteArray(Charsets.UTF_8))
  }
}

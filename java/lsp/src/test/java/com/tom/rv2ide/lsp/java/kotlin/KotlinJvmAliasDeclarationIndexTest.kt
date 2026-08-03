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
  fun kotlinSourceFiles_deduplicatesOverlappingSourceRoots() {
    val root = Files.createTempDirectory("kotlin-overlapping-source-roots")
    try {
      val nested = Files.createDirectories(root.resolve("nested"))
      write(root.resolve("Root.kt"), "package sample\nclass Root")
      write(nested.resolve("Nested.kt"), "package sample\nclass Nested")

      val files = KotlinJvmTypeIndex.kotlinSourceFiles(listOf(root.toFile(), nested.toFile()))
      val navigation = KotlinJvmTypeIndex.NavigationCandidateIndex.build(
        listOf(root.toFile(), nested.toFile()))

      assertEquals(2, files.size)
      // Ordering is by normalized full path, not bare file name: /root/Root.kt precedes
      // /root/nested/Nested.kt because the path separator sorts before the next file-name letter.
      assertEquals(listOf("Root.kt", "Nested.kt"), files.map { it.fileName.toString() })
      assertEquals(2, navigation.declarationCount())
      assertEquals("Root.kt", navigation.declaration("sample.Root")!!.file.fileName.toString())
      assertEquals("Nested.kt", navigation.declaration("sample.Nested")!!.file.fileName.toString())
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun navigationCandidateIndex_rejectsAmbiguousQualifiedDeclarations() {
    val root = Files.createTempDirectory("kotlin-navigation-ambiguity")
    try {
      write(root.resolve("First.kt"), "package sample\nclass Duplicate")
      write(root.resolve("Second.kt"), "package sample\nclass Duplicate")

      val index = KotlinJvmTypeIndex.NavigationCandidateIndex.build(listOf(root.toFile()))

      assertNull(index.declaration("sample.Duplicate"))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun navigationCandidateIndex_rejectsNormalAndMultifileFacadeJvmNameConflict() {
    val root = Files.createTempDirectory("kotlin-navigation-facade-conflict")
    try {
      write(root.resolve("Normal.kt"), """
        @file:JvmName("SharedFacade")
        package sample
        fun normal() = 1
      """.trimIndent())
      write(root.resolve("Multifile.kt"), """
        @file:JvmName("SharedFacade")
        @file:JvmMultifileClass
        package sample
        fun multifile() = 2
      """.trimIndent())

      val index = KotlinJvmTypeIndex.NavigationCandidateIndex.build(listOf(root.toFile()))

      assertNull(index.declaration("sample.SharedFacade"))
      assertTrue(index.multifileDeclarations("sample.SharedFacade").isEmpty())
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

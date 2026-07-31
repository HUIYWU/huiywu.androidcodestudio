package com.tom.rv2ide.lsp.java.kotlin

import com.itsaky.androidide.treesitter.TreeSitter
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import jdkx.lang.model.element.ExecutableElement
import jdkx.lang.model.element.TypeElement
import jdkx.lang.model.element.VariableElement
import jdkx.tools.JavaFileObject
import jdkx.tools.SimpleJavaFileObject
import openjdk.source.util.JavacTask
import openjdk.tools.javac.api.JavacTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Direct regression coverage for conservative Java ABI element to Kotlin source navigation. */
class KotlinJvmSourceNavigatorTest {

  @Test
  fun facadeNavigation_selectsGenericAliasOverloadByAttributedParameterType() {
    val kotlinSource =
      """
      package navigation

      typealias NavigationListAlias<T> = List<T>

      fun navigateAlias(value: NavigationListAlias<String>): Unit {}
      fun navigateAlias(value: NavigationListAlias<Int>): Unit {}
      """.trimIndent()
    val method = compileMethod(
      """
      package navigation;
      abstract class NavigationApiKt {
        abstract void navigateAlias(java.util.List<java.lang.Integer> value);
      }
      """.trimIndent(),
      "navigation.NavigationApiKt",
      "navigateAlias",
    )

    val location = KotlinJvmSourceNavigator.findFacadeMemberLocation(
      Paths.get("/navigation/NavigationApi.kt"), kotlinSource, method)

    assertNotNull(location)
  }

  @Test
  fun facadeNavigation_refusesAmbiguousUnsupportedKotlinTypes() {
    val kotlinSource =
      """
      package navigation

      fun ambiguous(value: (Int) -> Unit): Unit {}
      fun ambiguous(value: (String) -> Unit): Unit {}
      """.trimIndent()
    val method = compileMethod(
      """
      package navigation;
      abstract class AmbiguousApiKt {
        abstract void ambiguous(java.lang.Object value);
      }
      """.trimIndent(),
      "navigation.AmbiguousApiKt",
      "ambiguous",
    )

    val location = KotlinJvmSourceNavigator.findFacadeMemberLocation(
      Paths.get("/navigation/AmbiguousApi.kt"), kotlinSource, method)

    assertNull(location)
  }

  @Test
  fun facadeNavigation_resolvesJvmNamedFunction() {
    val kotlinSource =
      """
      package navigation

      @JvmName("loadNavigationValue")
      fun loadValue(id: Int): String = id.toString()
      """.trimIndent()
    val method = compileMethod(
      """
      package navigation;
      abstract class NamedNavigationApiKt {
        abstract java.lang.String loadNavigationValue(int id);
      }
      """.trimIndent(),
      "navigation.NamedNavigationApiKt",
      "loadNavigationValue",
    )

    val location = KotlinJvmSourceNavigator.findFacadeMemberLocation(
      Paths.get("/navigation/NamedNavigationApi.kt"), kotlinSource, method)

    assertNotNull(location)
  }

  @Test
  fun facadeNavigation_resolvesJvmNamedPropertyAccessors() {
    val kotlinSource =
      """
      package navigation

      @get:JvmName("readNavigationMode")
      @set:JvmName("writeNavigationMode")
      var navigationMode: String = "default"
      """.trimIndent()
    val getter = compileMethod(
      """
      package navigation;
      abstract class PropertyNavigationApiKt {
        abstract java.lang.String readNavigationMode();
        abstract void writeNavigationMode(java.lang.String value);
      }
      """.trimIndent(),
      "navigation.PropertyNavigationApiKt",
      "readNavigationMode",
    )
    val setter = compileMethod(
      """
      package navigation;
      abstract class PropertyNavigationApiKt {
        abstract java.lang.String readNavigationMode();
        abstract void writeNavigationMode(java.lang.String value);
      }
      """.trimIndent(),
      "navigation.PropertyNavigationApiKt",
      "writeNavigationMode",
    )

    assertNotNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(
      Paths.get("/navigation/PropertyNavigationApi.kt"), kotlinSource, getter))
    assertNotNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(
      Paths.get("/navigation/PropertyNavigationApi.kt"), kotlinSource, setter))
  }

  @Test
  fun facadeNavigation_rejectsJvmSyntheticDeclarationAndAccessor() {
    val kotlinSource =
      """
      package navigation

      @JvmSynthetic
      fun hiddenNavigation(): String = "hidden"

      @get:JvmSynthetic
      var guardedNavigation: String = "guarded"
      """.trimIndent()
    val hiddenFunction = compileMethod(
      """
      package navigation;
      abstract class SyntheticNavigationApiKt {
        abstract java.lang.String hiddenNavigation();
      }
      """.trimIndent(),
      "navigation.SyntheticNavigationApiKt",
      "hiddenNavigation",
    )
    val hiddenGetter = compileMethod(
      """
      package navigation;
      abstract class SyntheticNavigationApiKt {
        abstract java.lang.String getGuardedNavigation();
      }
      """.trimIndent(),
      "navigation.SyntheticNavigationApiKt",
      "getGuardedNavigation",
    )

    assertNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(
      Paths.get("/navigation/SyntheticNavigationApi.kt"), kotlinSource, hiddenFunction))
    assertNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(
      Paths.get("/navigation/SyntheticNavigationApi.kt"), kotlinSource, hiddenGetter))
  }

  @Test
  fun typeNavigation_distinguishesPrimaryAndSecondaryConstructors() {
    val kotlinSource =
      """
      package navigation

      class ConstructorNavigation(val name: String, val count: Int = 0) {
        constructor(code: Int) : this(code.toString())
      }
      """.trimIndent()
    val primary = compileConstructor(
      """
      package navigation;
      class ConstructorNavigation {
        ConstructorNavigation(java.lang.String name, int count) {}
        ConstructorNavigation(int code) {}
      }
      """.trimIndent(),
      "navigation.ConstructorNavigation",
      "java.lang.String,int",
    )
    val secondary = compileConstructor(
      """
      package navigation;
      class ConstructorNavigation {
        ConstructorNavigation(java.lang.String name, int count) {}
        ConstructorNavigation(int code) {}
      }
      """.trimIndent(),
      "navigation.ConstructorNavigation",
      "int",
    )

    assertNotNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
      Paths.get("/navigation/ConstructorNavigation.kt"), kotlinSource, primary))
    assertNotNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
      Paths.get("/navigation/ConstructorNavigation.kt"), kotlinSource, secondary))
  }

  @Test
  fun typeNavigation_resolvesNestedAndInnerTypes() {
    val kotlinSource =
      """
      package navigation

      class TypeNavigation {
        class NestedNavigation
        inner class InnerNavigation
      }
      """.trimIndent()
    val nested = compileType(
      """
      package navigation;
      class TypeNavigation {
        static class NestedNavigation {}
        class InnerNavigation {}
      }
      """.trimIndent(),
      "navigation.TypeNavigation.NestedNavigation",
    )
    val inner = compileType(
      """
      package navigation;
      class TypeNavigation {
        static class NestedNavigation {}
        class InnerNavigation {}
      }
      """.trimIndent(),
      "navigation.TypeNavigation.InnerNavigation",
    )

    assertNotNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
      Paths.get("/navigation/TypeNavigation.kt"), kotlinSource, nested))
    assertNotNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
      Paths.get("/navigation/TypeNavigation.kt"), kotlinSource, inner))
  }

  @Test
  fun typeNavigation_resolvesCompanionJvmStaticFunction() {
    val kotlinSource =
      """
      package navigation

      class CompanionNavigation {
        companion object {
          @JvmStatic
          fun createNavigation(value: String): CompanionNavigation = CompanionNavigation()
        }
      }
      """.trimIndent()
    val method = compileMethod(
      """
      package navigation;
      class CompanionNavigation {
        static CompanionNavigation createNavigation(java.lang.String value) { return null; }
      }
      """.trimIndent(),
      "navigation.CompanionNavigation",
      "createNavigation",
    )

    assertNotNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
      Paths.get("/navigation/CompanionNavigation.kt"), kotlinSource, method))
  }

  @Test
  fun typeNavigation_resolvesJvmOverloadsPrimaryConstructorVariants() {
    val kotlinSource =
      """
      package navigation

      class OverloadedNavigation @JvmOverloads constructor(
        val name: String,
        val count: Int = 0,
        val enabled: Boolean = true
      )
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      class OverloadedNavigation {
        OverloadedNavigation(java.lang.String name, int count, boolean enabled) {}
        OverloadedNavigation(java.lang.String name, int count) {}
        OverloadedNavigation(java.lang.String name) {}
      }
      """.trimIndent()

    for (parameters in listOf(
      "java.lang.String,int,boolean",
      "java.lang.String,int",
      "java.lang.String",
    )) {
      val constructor = compileConstructor(javaSource, "navigation.OverloadedNavigation", parameters)
      assertNotNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
        Paths.get("/navigation/OverloadedNavigation.kt"), kotlinSource, constructor))
    }
  }

  @Test
  fun typeNavigation_resolvesConstructorPropertyAccessors() {
    val kotlinSource =
      """
      package navigation

      class ConstructorPropertyNavigation(
        val navigationId: String,
        var navigationEnabled: Boolean
      )
      """.trimIndent()
    val javaSource =
      """
      package navigation;
      abstract class ConstructorPropertyNavigation {
        abstract java.lang.String getNavigationId();
        abstract boolean getNavigationEnabled();
        abstract void setNavigationEnabled(boolean value);
      }
      """.trimIndent()

    for (name in listOf("getNavigationId", "getNavigationEnabled", "setNavigationEnabled")) {
      val method = compileMethod(javaSource, "navigation.ConstructorPropertyNavigation", name)
      assertNotNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
        Paths.get("/navigation/ConstructorPropertyNavigation.kt"), kotlinSource, method))
    }
  }

  @Test
  fun typeNavigation_resolvesCompanionJvmField() {
    val kotlinSource =
      """
      package navigation

      class FieldNavigation {
        companion object {
          @JvmField
          val NAVIGATION_VERSION: Int = 1
        }
      }
      """.trimIndent()
    val field = compileField(
      """
      package navigation;
      class FieldNavigation {
        static int NAVIGATION_VERSION;
      }
      """.trimIndent(),
      "navigation.FieldNavigation",
      "NAVIGATION_VERSION",
    )

    assertNotNull(KotlinJvmSourceNavigator.findTypeMemberLocation(
      Paths.get("/navigation/FieldNavigation.kt"), kotlinSource, field))
  }

  @Test
  fun multifileNavigation_selectsMemberFromCorrectFacadePart() {
    val firstPath = Paths.get("/navigation/MultifileFirst.kt")
    val secondPath = Paths.get("/navigation/MultifileSecond.kt")
    val firstSource =
      """
      @file:JvmName("NavigationFacade")
      @file:JvmMultifileClass
      package navigation

      fun firstNavigation(value: Int): String = value.toString()
      """.trimIndent()
    val secondSource =
      """
      @file:JvmName("NavigationFacade")
      @file:JvmMultifileClass
      package navigation

      fun secondNavigation(value: String): Boolean = value.isNotEmpty()
      """.trimIndent()
    val method = compileMethod(
      """
      package navigation;
      abstract class NavigationFacade {
        abstract boolean secondNavigation(java.lang.String value);
      }
      """.trimIndent(),
      "navigation.NavigationFacade",
      "secondNavigation",
    )

    val location = KotlinJvmSourceNavigator.findMultifileFacadeMemberLocation(
      listOf(firstPath, secondPath), listOf(firstSource, secondSource), method)

    assertNotNull(location)
    assertEquals(secondPath, location!!.file)
    assertEquals("secondNavigation", sourceTextAt(secondSource, location))
  }

  @Test
  fun multifileNavigation_usesAliasContextOfEachPart() {
    val firstPath = Paths.get("/navigation/AliasFirst.kt")
    val secondPath = Paths.get("/navigation/AliasSecond.kt")
    val firstSource =
      """
      @file:JvmName("AliasNavigationFacade")
      @file:JvmMultifileClass
      package navigation

      typealias PartAlias<T> = List<T>
      fun aliasNavigation(value: PartAlias<String>): Unit {}
      """.trimIndent()
    val secondSource =
      """
      @file:JvmName("AliasNavigationFacade")
      @file:JvmMultifileClass
      package navigation

      typealias PartAlias<T> = List<T>
      fun aliasNavigation(value: PartAlias<Int>): Unit {}
      """.trimIndent()
    val method = compileMethod(
      """
      package navigation;
      abstract class AliasNavigationFacade {
        abstract void aliasNavigation(java.util.List<java.lang.Integer> value);
      }
      """.trimIndent(),
      "navigation.AliasNavigationFacade",
      "aliasNavigation",
    )

    val location = KotlinJvmSourceNavigator.findMultifileFacadeMemberLocation(
      listOf(firstPath, secondPath), listOf(firstSource, secondSource), method)

    assertNotNull(location)
    assertEquals(secondPath, location!!.file)
    assertEquals("aliasNavigation", sourceTextAt(secondSource, location))
  }

  @Test
  fun crossFileAliasVisibility_drivesFacadeNavigation() {
    val root = Files.createTempDirectory("kotlin-navigation-alias")
    try {
      val shared = root.resolve("shared").also { Files.createDirectories(it) }
      val consumer = root.resolve("consumer").also { Files.createDirectories(it) }
      writeSource(shared.resolve("Aliases.kt"), """
        package shared
        typealias ImportedNavigationAlias<T> = List<T>
      """.trimIndent())
      val consumerFile = consumer.resolve("NavigationApi.kt")
      val consumerSource =
        """
        package consumer
        import shared.ImportedNavigationAlias

        fun crossFileNavigation(value: ImportedNavigationAlias<String>): Unit {}
        fun crossFileNavigation(value: ImportedNavigationAlias<Int>): Unit {}
        """.trimIndent()
      writeSource(consumerFile, consumerSource)
      val aliases = KotlinJvmTypeIndex.visibleGenericTypeAliases(
        listOf(root.toFile()), consumerFile)
      val method = compileMethod(
        """
        package consumer;
        abstract class NavigationApiKt {
          abstract void crossFileNavigation(java.util.List<java.lang.Integer> value);
        }
        """.trimIndent(),
        "consumer.NavigationApiKt",
        "crossFileNavigation",
      )

      val location = KotlinJvmSourceNavigator.findFacadeMemberLocation(
        consumerFile, consumerSource, method, emptyMap(), aliases)

      assertNotNull(location)
      assertEquals("crossFileNavigation", sourceTextAt(consumerSource, location!!))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun wildcardAliasVisibility_doesNotEnableAmbiguousNavigation() {
    val root = Files.createTempDirectory("kotlin-navigation-wildcard-alias")
    try {
      val shared = root.resolve("shared").also { Files.createDirectories(it) }
      val consumer = root.resolve("consumer").also { Files.createDirectories(it) }
      writeSource(shared.resolve("Aliases.kt"), """
        package shared
        typealias WildcardNavigationAlias<T> = List<T>
      """.trimIndent())
      val consumerFile = consumer.resolve("NavigationApi.kt")
      val consumerSource =
        """
        package consumer
        import shared.*

        fun wildcardNavigation(value: WildcardNavigationAlias<String>): Unit {}
        fun wildcardNavigation(value: WildcardNavigationAlias<Int>): Unit {}
        """.trimIndent()
      writeSource(consumerFile, consumerSource)
      val aliases = KotlinJvmTypeIndex.visibleGenericTypeAliases(
        listOf(root.toFile()), consumerFile)
      val method = compileMethod(
        """
        package consumer;
        abstract class NavigationApiKt {
          abstract void wildcardNavigation(java.util.List<java.lang.Integer> value);
        }
        """.trimIndent(),
        "consumer.NavigationApiKt",
        "wildcardNavigation",
      )

      assertTrue(aliases.isEmpty())
      assertNull(KotlinJvmSourceNavigator.findFacadeMemberLocation(
        consumerFile, consumerSource, method, emptyMap(), aliases))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun compileMethod(
    source: String,
    qualifiedOwner: String,
    methodName: String,
  ): ExecutableElement {
    val task =
      JavacTool.create()
        .getTask(
          null,
          null,
          null,
          emptyList<String>(),
          emptyList<String>(),
          listOf(FakeSourceFile(source)),
        ) as JavacTask
    task.parse()
    task.analyze()
    val owner = task.elements.getTypeElement(qualifiedOwner) as TypeElement
    return owner.enclosedElements
      .filterIsInstance<ExecutableElement>()
      .single { it.simpleName.contentEquals(methodName) }
  }

  private fun writeSource(path: Path, source: String) {
    Files.write(path, source.toByteArray(Charsets.UTF_8))
  }

  private fun sourceTextAt(source: String, location: com.tom.rv2ide.models.Location): String {
    val start = location.range.start
    val end = location.range.end
    if (start.line != end.line) return ""
    return source.lineSequence().elementAt(start.line).substring(start.column, end.column)
  }

  private fun compileField(
    source: String,
    qualifiedOwner: String,
    fieldName: String,
  ): VariableElement {
    val owner = compileType(source, qualifiedOwner)
    return owner.enclosedElements
      .filterIsInstance<VariableElement>()
      .single { it.simpleName.contentEquals(fieldName) }
  }

  private fun compileConstructor(
    source: String,
    qualifiedOwner: String,
    parameterTypes: String,
  ): ExecutableElement {
    val owner = compileType(source, qualifiedOwner)
    return owner.enclosedElements
      .filterIsInstance<ExecutableElement>()
      .single { constructor ->
        constructor.simpleName.contentEquals("<init>") &&
          constructor.parameters.joinToString(",") { it.asType().toString() } == parameterTypes
      }
  }

  private fun compileType(source: String, qualifiedName: String): TypeElement {
    val task = compileTask(source)
    return task.elements.getTypeElement(qualifiedName) as TypeElement
  }

  private fun compileTask(source: String): JavacTask {
    val task =
      JavacTool.create()
        .getTask(
          null,
          null,
          null,
          emptyList<String>(),
          emptyList<String>(),
          listOf(FakeSourceFile(source)),
        ) as JavacTask
    task.parse()
    task.analyze()
    return task
  }

  private class FakeSourceFile(private val code: String) :
    SimpleJavaFileObject(URI.create("string:///NavigationApiKt.java"), JavaFileObject.Kind.SOURCE) {
    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = code
    override fun getLastModified(): Long = 1L
  }

  companion object {
    @JvmStatic
    @BeforeClass
    fun loadParserLibraries() {
      TreeSitter.loadLibrary()
      System.loadLibrary("tree-sitter-kotlin")
    }
  }
}

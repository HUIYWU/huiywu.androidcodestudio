package com.tom.rv2ide.lsp.java.kotlin

import com.itsaky.androidide.treesitter.TreeSitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinJvmAbiStubGeneratorTest {

  @Test
  fun generate_projectsConstructorPropertiesAndMembers() {
    val source =
        """
        package sample

        class Person(val name: String, var age: Int) {
          fun greet(prefix: String): String = prefix + name
          val enabled: Boolean = true
          var score: Long = 0
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generate("sample.Person", "Person.kt", source)

    assertNotNull(stub)
    assertContains(stub!!, "package sample;")
    assertContains(stub, "public class Person")
    assertContains(stub, "public Person(String name, int age)")
    assertContains(stub, "public String getName()")
    assertContains(stub, "public int getAge()")
    assertContains(stub, "public void setAge(int value)")
    assertContains(stub, "public String greet(String prefix)")
    assertContains(stub, "public boolean getEnabled()")
    assertContains(stub, "public long getScore()")
    assertContains(stub, "public void setScore(long value)")
  }

  @Test
  fun generate_projectsBooleanIsPropertiesWithJvmAccessorNames() {
    val classSource =
        """
        package sample

        class Status(var isReady: Boolean, val isOptional: Boolean?) {
          var isActive: Boolean = true
          val issue: Boolean = false
          var isLabel: String = "label"

          companion object {
            @JvmStatic var isOnline: Boolean = true
          }
        }
        """.trimIndent()
    val facadeSource =
        """
        package sample

        var isFeatureEnabled: Boolean = true
        """.trimIndent()

    val classStub = KotlinJvmAbiStubGenerator.generate("sample.Status", "Status.kt", classSource)
    val facadeStub =
        KotlinJvmAbiStubGenerator.generate("sample.FeaturesKt", "Features.kt", facadeSource)

    assertNotNull(classStub)
    assertContains(classStub!!, "public boolean isReady()")
    assertContains(classStub, "public void setReady(boolean value)")
    assertContains(classStub, "public boolean isActive()")
    assertContains(classStub, "public void setActive(boolean value)")
    assertContains(classStub, "public Boolean getIsOptional()")
    assertContains(classStub, "public boolean getIssue()")
    assertContains(classStub, "public String getIsLabel()")
    assertContains(classStub, "public void setIsLabel(String value)")
    assertContains(classStub, "public static boolean isOnline()")
    assertContains(classStub, "public static void setOnline(boolean value)")
    assertFalse(classStub.contains("getIsActive()"))
    assertFalse(classStub.contains("setIsActive(boolean value)"))

    assertNotNull(facadeStub)
    assertContains(facadeStub!!, "public static boolean isFeatureEnabled()")
    assertContains(facadeStub, "public static void setFeatureEnabled(boolean value)")
    assertFalse(facadeStub.contains("getIsFeatureEnabled()"))
  }

  @Test
  fun generate_projectsJvmNameForFunctionsAndProperties() {
    val source =
        """
        package sample

        @JvmName("loadValue")
        fun load(): String = "value"

        @JvmName("fetchValue")
        @JvmOverloads
        fun fetch(id: Int, suffix: String = ""): String = id.toString() + suffix

        @JvmName("renderText")
        fun String.render(): String = this

        @get:JvmName("readMode")
        @set:JvmName("writeMode")
        var mode: String = "default"
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generate("sample.NamedApiKt", "NamedApi.kt", source)

    assertNotNull(stub)
    assertContains(stub!!, "public static String loadValue()")
    assertContains(stub, "public static String fetchValue(int id, String suffix)")
    assertContains(stub, "public static String fetchValue(int id)")
    assertContains(stub, "public static String renderText(String receiver)")
    assertContains(stub, "public static String readMode()")
    assertContains(stub, "public static void writeMode(String value)")
    assertFalse(stub.contains("public static String load()"))
    assertFalse(stub.contains("public static String fetch("))
    assertFalse(stub.contains("public static String getMode()"))
  }

  @Test
  fun generate_projectsTopLevelMembersIntoDefaultFileFacade() {
    val source =
        """
        package sample

        fun create(name: String, count: Int): String = name
        val version: Int = 1
        var enabled: Boolean = true
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generate("sample.UtilitiesKt", "Utilities.kt", source)

    assertNotNull(stub)
    assertContains(stub!!, "public final class UtilitiesKt")
    assertContains(stub, "public static String create(String name, int count)")
    assertContains(stub, "public static int getVersion()")
    assertContains(stub, "public static boolean getEnabled()")
    assertContains(stub, "public static void setEnabled(boolean value)")
  }

  @Test
  fun generate_honorsFileJvmName() {
    val source =
        """
        @file:JvmName("Api")
        package sample

        fun load(): String = "value"
        """.trimIndent()

    val renamed = KotlinJvmAbiStubGenerator.generate("sample.Api", "Utilities.kt", source)
    val defaultFacade = KotlinJvmAbiStubGenerator.generate("sample.UtilitiesKt", "Utilities.kt", source)

    assertNotNull(renamed)
    assertContains(renamed!!, "public final class Api")
    assertContains(renamed, "public static String load()")
    assertNull(defaultFacade)
  }

  @Test
  fun generate_projectsObjectAndCompanionJvmSurface() {
    val objectSource =
        """
        package sample

        object Registry {
          fun find(id: Int): String = id.toString()
        }
        """.trimIndent()
    val classSource =
        """
        package sample

        class Config {
          companion object {
            @JvmStatic fun create(name: String): Config = Config()
            @JvmStatic @JvmName("buildConfig") fun named(name: String): Config = Config()
            @get:JvmName("readOnline")
            @set:JvmName("writeOnline")
            @JvmStatic var online: Boolean = true
            @JvmField val VERSION: Int = 1
            fun ordinary(): String = "value"
          }
        }
        """.trimIndent()

    val objectStub = KotlinJvmAbiStubGenerator.generate("sample.Registry", "Registry.kt", objectSource)
    val classStub = KotlinJvmAbiStubGenerator.generate("sample.Config", "Config.kt", classSource)

    assertNotNull(objectStub)
    assertContains(objectStub!!, "public static final Registry INSTANCE")
    assertContains(objectStub, "public String find(int id)")

    assertNotNull(classStub)
    assertContains(classStub!!, "public static final class Companion")
    assertContains(classStub, "public static final Companion Companion")
    assertContains(classStub, "public static Config create(String name)")
    assertContains(classStub, "public static Config buildConfig(String name)")
    assertContains(classStub, "public static boolean readOnline()")
    assertContains(classStub, "public static void writeOnline(boolean value)")
    assertContains(classStub, "public static int VERSION;")
    assertContains(classStub, "public String ordinary()")
  }

  @Test
  fun generate_projectsOrdinaryNestedTypes() {
    val source =
        """
        package sample

        class Outer {
          class Nested(val value: String) {
            fun label(): String = value

            class Deep(val count: Int)
          }

          interface Listener {
            fun onChanged(value: Int)
          }

          object Defaults {
            val enabled: Boolean = true
          }

          inner class Entry(val name: String)
          private class Hidden
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generate("sample.Outer", "Outer.kt", source)

    assertNotNull(stub)
    assertContains(stub!!, "public static class Nested")
    assertContains(stub, "public Nested(String value)")
    assertFalse(stub.contains("protected Nested()"))
    assertFalse(stub.contains("public Nested()"))
    assertContains(stub, "public String getValue()")
    assertContains(stub, "public String label()")
    assertContains(stub, "public static class Deep")
    assertContains(stub, "public Deep(int count)")
    assertContains(stub, "public static interface Listener")
    assertContains(stub, "public void onChanged(int value)")
    assertContains(stub, "public static class Defaults")
    assertContains(stub, "public static final Defaults INSTANCE")
    assertContains(stub, "public boolean getEnabled()")
    assertFalse(stub.contains("class Entry"))
    assertFalse(stub.contains("class Hidden"))
  }

  @Test
  fun generate_projectsNullablePrimitivesCollectionsAndArrays() {
    val source =
        """
        package sample

        class TypeSamples(
          val nullableCount: Int?,
          val names: List<String>,
          val lookup: Map<String, Int>,
          val numbers: IntArray,
          val labels: Array<String>
        ) {
          fun transform(values: MutableList<Long?>): Set<Int> = emptySet()
          fun wildcard(values: List<*>): Collection<out Int> = emptyList()
          fun unknown(callback: (String) -> Int): Any = callback
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generate("sample.TypeSamples", "TypeSamples.kt", source)

    assertNotNull(stub)
    assertContains(
        stub!!,
        "public TypeSamples(Integer nullableCount, java.util.List<String> names, " +
            "java.util.Map<String, Integer> lookup, int[] numbers, String[] labels)")
    assertContains(stub, "public Integer getNullableCount()")
    assertContains(stub, "public java.util.List<String> getNames()")
    assertContains(stub, "public java.util.Map<String, Integer> getLookup()")
    assertContains(stub, "public int[] getNumbers()")
    assertContains(stub, "public String[] getLabels()")
    assertContains(stub, "public java.util.Set<Integer> transform(java.util.List<Long> values)")
    assertContains(
        stub,
        "public java.util.Collection<? extends Integer> wildcard(java.util.List<?> values)")
    assertContains(stub, "public Object unknown(Object callback)")
  }

  @Test
  fun generate_preservesResolvableUserDeclaredTypes() {
    val source =
        """
        package sample

        import android.content.Context
        import java.time.Instant as TimePoint

        class Profile(val id: String)

        class Repository(val context: Context) {
          fun load(profile: Profile): Profile = profile
          fun history(): List<Profile> = emptyList()
          fun timestamp(): TimePoint? = null
          fun qualified(): java.util.Locale? = null
          fun callback(): (Profile) -> Profile = { it }
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generate("sample.Repository", "Repository.kt", source)

    assertNotNull(stub)
    assertContains(stub!!, "public Repository(android.content.Context context)")
    assertContains(stub, "public Profile load(Profile profile)")
    assertContains(stub, "public java.util.List<Profile> history()")
    assertContains(stub, "public java.time.Instant timestamp()")
    assertContains(stub, "public java.util.Locale qualified()")
    assertContains(stub, "public Object callback()")
  }

  @Test
  fun generate_preservesKnownSamePackageTypeFromAnotherFile() {
    val source =
        """
        package sample

        class Repository {
          fun load(): Profile = error("not built")
        }
        """.trimIndent()

    val stub =
        KotlinJvmAbiStubGenerator.generate(
            "sample.Repository", "Repository.kt", source, setOf("sample.Repository", "sample.Profile"))

    assertNotNull(stub)
    assertContains(stub!!, "public Profile load()")
  }

  @Test
  fun generate_projectsClassAndMethodTypeParameters() {
    val source =
        """
        package sample

        class Box<T : CharSequence>(val value: T) {
          fun getOr(defaultValue: T): T = value
          fun <R> map(value: R): List<R> = listOf(value)
          fun <N : Number> number(value: N): N = value
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generate("sample.Box", "Box.kt", source)

    assertNotNull(stub)
    assertContains(stub!!, "public class Box<T extends CharSequence>")
    assertContains(stub, "public Box(T value)")
    assertContains(stub, "public T getValue()")
    assertContains(stub, "public T getOr(T defaultValue)")
    assertContains(stub, "public <R> java.util.List<R> map(R value)")
    assertContains(stub, "public <N extends Number> N number(N value)")
  }

  @Test
  fun generate_projectsClassAndInterfaceInheritance() {
    val source =
        """
        package sample

        interface Named
        interface Tagged<T>
        open class Base(val label: String)

        class User<T>(val id: T) : Base("user"), Named, Tagged<T>
        interface Detailed : Named, Tagged<String>
        """.trimIndent()

    val baseStub =
        KotlinJvmAbiStubGenerator.generate(
            "sample.Base",
            "Models.kt",
            source,
            setOf("sample.Named", "sample.Tagged", "sample.Base", "sample.User", "sample.Detailed"))
    val userStub =
        KotlinJvmAbiStubGenerator.generate(
            "sample.User",
            "Models.kt",
            source,
            setOf("sample.Named", "sample.Tagged", "sample.Base", "sample.User", "sample.Detailed"))
    val interfaceStub =
        KotlinJvmAbiStubGenerator.generate(
            "sample.Detailed",
            "Models.kt",
            source,
            setOf("sample.Named", "sample.Tagged", "sample.Base", "sample.User", "sample.Detailed"))

    assertNotNull(baseStub)
    assertContains(baseStub!!, "protected Base()")
    assertContains(baseStub, "__kotlin_abi_synthetic_constructor__")
    assertContains(baseStub, "public Base(String label)")

    assertNotNull(userStub)
    assertContains(
        userStub!!,
        "public class User<T> extends Base implements Named, Tagged<T>")
    assertContains(userStub, "public User(T id)")

    assertNotNull(interfaceStub)
    assertContains(
        interfaceStub!!,
        "public interface Detailed extends Named, Tagged<String>")
  }

  @Test
  fun generate_projectsSecondaryConstructorsAndVisibility() {
    val source =
        """
        package sample

        class Token private constructor(val raw: String) {
          constructor(code: Int) : this(code.toString())
          protected constructor(flag: Boolean) : this(flag.toString())
          private constructor(value: Long) : this(value.toString())
          constructor(vararg parts: String) : this(parts.joinToString())
        }

        class Legacy {
          constructor(value: String)
        }
        """.trimIndent()

    val tokenStub = KotlinJvmAbiStubGenerator.generate("sample.Token", "Tokens.kt", source)
    val legacyStub = KotlinJvmAbiStubGenerator.generate("sample.Legacy", "Tokens.kt", source)

    assertNotNull(tokenStub)
    assertContains(tokenStub!!, "protected Token()")
    assertContains(tokenStub, "private Token(String raw)")
    assertContains(tokenStub, "public Token(int code)")
    assertContains(tokenStub, "protected Token(boolean flag)")
    assertContains(tokenStub, "private Token(long value)")
    assertContains(tokenStub, "public Token(String... parts)")

    assertNotNull(legacyStub)
    assertContains(legacyStub!!, "protected Legacy()")
    assertContains(legacyStub, "public Legacy(String value)")
    assertFalse(legacyStub.contains("public Legacy()"))
  }

  @Test
  fun generate_projectsJvmOverloadsConstructors() {
    val source =
        """
        package sample

        class Request @JvmOverloads constructor(
          val path: String,
          val retries: Int = 3,
          val secure: Boolean = true
        )

        class Defaults @JvmOverloads constructor(
          val name: String = "default",
          val count: Int = 1
        )

        class Alternate private constructor(val value: String) {
          @JvmOverloads
          protected constructor(code: Int, enabled: Boolean = true, label: String = "") :
              this(code.toString())
        }

        class Plain(val name: String = "plain")
        """.trimIndent()

    val requestStub = KotlinJvmAbiStubGenerator.generate("sample.Request", "Requests.kt", source)
    val defaultsStub = KotlinJvmAbiStubGenerator.generate("sample.Defaults", "Requests.kt", source)
    val alternateStub = KotlinJvmAbiStubGenerator.generate("sample.Alternate", "Requests.kt", source)
    val plainStub = KotlinJvmAbiStubGenerator.generate("sample.Plain", "Requests.kt", source)

    assertNotNull(requestStub)
    assertContains(requestStub!!, "public Request(String path, int retries, boolean secure)")
    assertContains(requestStub, "public Request(String path, int retries)")
    assertContains(requestStub, "public Request(String path)")

    assertNotNull(defaultsStub)
    assertContains(defaultsStub!!, "public Defaults(String name, int count)")
    assertContains(defaultsStub, "public Defaults(String name)")
    assertContains(defaultsStub, "public Defaults()")
    assertFalse(defaultsStub.contains("protected Defaults()"))
    assertFalse(defaultsStub.contains("__kotlin_abi_synthetic_constructor__"))

    assertNotNull(alternateStub)
    assertContains(alternateStub!!, "private Alternate(String value)")
    assertContains(alternateStub, "protected Alternate(int code, boolean enabled, String label)")
    assertContains(alternateStub, "protected Alternate(int code, boolean enabled)")
    assertContains(alternateStub, "protected Alternate(int code)")

    assertNotNull(plainStub)
    assertContains(plainStub!!, "public Plain(String name)")
    assertFalse(plainStub.contains("public Plain()"))
  }

  @Test
  fun generate_projectsVarargParameters() {
    val classSource =
        """
        package sample

        class Varargs {
          fun names(vararg values: String): String = values.joinToString()
          fun ints(prefix: String, vararg values: Int): Int = values.size
          fun <T> collect(vararg values: T): List<T> = values.toList()
          fun middle(vararg values: String, suffix: String): String = suffix
          fun marker(first: String = "vararg", second: String): String = second
          @JvmOverloads
          fun overloaded(vararg values: String, suffix: String = ""): String = suffix
        }
        """.trimIndent()
    val facadeSource =
        """
        package sample

        fun join(vararg values: String): String = values.joinToString()
        """.trimIndent()

    val classStub =
        KotlinJvmAbiStubGenerator.generate("sample.Varargs", "Varargs.kt", classSource)
    val facadeStub =
        KotlinJvmAbiStubGenerator.generate("sample.FunctionsKt", "Functions.kt", facadeSource)

    assertNotNull(classStub)
    assertContains(classStub!!, "public String names(String... values)")
    assertContains(classStub, "public int ints(String prefix, int... values)")
    assertContains(classStub, "public <T> java.util.List<T> collect(T... values)")
    assertContains(classStub, "public String middle(String[] values, String suffix)")
    assertContains(classStub, "public String marker(String first, String second)")
    assertContains(classStub, "public String overloaded(String[] values, String suffix)")
    assertContains(classStub, "public String overloaded(String[] values)")

    assertNotNull(facadeStub)
    assertContains(facadeStub!!, "public static String join(String... values)")
  }

  @Test
  fun generate_excludesPrivateAndMismatchedDeclarations() {
    val privateType =
        """
        package sample
        private class Hidden
        """.trimIndent()
    val publicType =
        """
        package other
        class Visible
        """.trimIndent()

    assertNull(KotlinJvmAbiStubGenerator.generate("sample.Hidden", "Hidden.kt", privateType))
    assertNull(KotlinJvmAbiStubGenerator.generate("sample.Visible", "Visible.kt", publicType))
  }

  @Test
  fun generate_excludesPrivateMembers() {
    val source =
        """
        package sample

        class Service {
          private fun secret(): String = "hidden"
          fun visible(): String = "visible"
          private val token: String = "token"
        }
        """.trimIndent()

    val stub = KotlinJvmAbiStubGenerator.generate("sample.Service", "Service.kt", source)

    assertNotNull(stub)
    assertContains(stub!!, "public String visible()")
    assertFalse(stub.contains("secret("))
    assertFalse(stub.contains("getToken("))
  }

  @Test
  fun generate_structuredAndFallbackHaveJvmSurfaceParity() {
    TreeSitter.loadLibrary()
    System.loadLibrary("tree-sitter-kotlin")

    val cases =
        listOf(
            AbiParityCase(
                "class members and resolved types",
                "sample.Service",
                "Service.kt",
                """
                package sample

                open class Base<T>
                class Service(val name: String, var count: Int) : Base<String>() {
                  fun labels(values: List<String?>): List<String?> = values
                  fun <T> echo(value: T): T = value
                  val enabled: Boolean = true
                }
                """.trimIndent()),
            AbiParityCase(
                "JVM names and companion members",
                "sample.Named",
                "Named.kt",
                """
                package sample

                class Named(var isActive: Boolean) {
                  @get:JvmName("readMode")
                  @set:JvmName("writeMode")
                  var mode: String = "default"

                  @JvmName("loadValue")
                  fun load(): String = mode

                  companion object {
                    @JvmStatic fun create(value: String): Named = Named(value.isNotEmpty())
                    @JvmField val version: Int = 1
                  }
                }
                """.trimIndent()),
            AbiParityCase(
                "top-level facade JVM names",
                "sample.NamedApiKt",
                "NamedApi.kt",
                """
                package sample

                @JvmName("loadValue")
                fun load(): String = "value"

                @get:JvmName("readMode")
                @set:JvmName("writeMode")
                var mode: String = "default"

                var isFeatureEnabled: Boolean = true
                """.trimIndent()),
            AbiParityCase(
                "constructors and nested types",
                "sample.Outer",
                "Outer.kt",
                """
                package sample

                class Outer @JvmOverloads constructor(val id: String, val count: Int = 0) {
                  constructor(id: Int) : this(id.toString())

                  class Nested(val value: String) {
                    class Deep(val count: Int)
                  }
                  interface Listener { fun onChanged(value: Int) }
                  object Defaults { val enabled: Boolean = true }
                  inner class Entry(val name: String)
                  private class Hidden
                }
                """.trimIndent()))

    for (case in cases) {
      val structured =
          KotlinJvmAbiStubGenerator.generateForTest(
              case.qualifiedName,
              case.fileName,
              case.source,
              emptySet(),
              KotlinJvmAbiStubGenerator.GenerationMode.STRUCTURED)
      val fallback =
          KotlinJvmAbiStubGenerator.generateForTest(
              case.qualifiedName,
              case.fileName,
              case.source,
              emptySet(),
              KotlinJvmAbiStubGenerator.GenerationMode.FALLBACK)

      assertNotNull("Structured generation failed for ${case.description}", structured)
      assertNotNull("Fallback generation failed for ${case.description}", fallback)
      assertEquals(
          "JVM ABI surface differs for ${case.description}\n" +
              "Structured stub:\n$structured\nFallback stub:\n$fallback",
          jvmSurface(structured!!),
          jvmSurface(fallback!!))
    }
  }

  private data class AbiParityCase(
      val description: String,
      val qualifiedName: String,
      val fileName: String,
      val source: String)

  /**
   * Converts generated Java into an order-independent declaration surface. Generator stubs place
   * declarations on individual lines, so this intentionally ignores formatting and method bodies
   * while retaining the declaring type, modifiers, names, parameter types, and return types.
   */
  private fun jvmSurface(stub: String): Set<String> {
    val result = linkedSetOf<String>()
    val owners = mutableListOf<String>()
    val typePattern =
        Regex(
            "(?:public\\s+)?(?:static\\s+)?(?:final\\s+)?" +
                "(class|interface|enum|@interface)\\s+([A-Za-z_$][\\w$]*)")

    for (rawLine in stub.lineSequence()) {
      val line = rawLine.trim().replace(Regex("\\s+"), " ")
      if (line.isEmpty() || line.startsWith("package ")) continue

      val type = typePattern.find(line)
      if (type != null) {
        val owner = (owners + type.groupValues[2]).joinToString(".")
        val declaration = line.substringBefore('{').trim()
        result += "type:$owner:$declaration"
        if (line.contains('{') && !line.contains("{}") && !line.contains("{ ; }")) {
          owners += type.groupValues[2]
        }
        continue
      }

      if (line == "}") {
        if (owners.isNotEmpty()) owners.removeAt(owners.lastIndex)
        continue
      }
      if (owners.isEmpty()) continue

      val declaration = canonicalMemberDeclaration(line)
      if (declaration.isNotEmpty()) {
        result += "member:${owners.joinToString(".")}:$declaration"
      }
    }
    return result.toSortedSet()
  }

  private fun canonicalMemberDeclaration(line: String): String {
    val declaration =
        line
            .substringBefore(" { return ")
            .substringBefore(" { throw ")
            .removeSuffix(" {}")
            .removeSuffix(";")
            .trim()
    val open = declaration.indexOf('(')
    val close = declaration.lastIndexOf(')')
    if (open < 0 || close < open) {
      return declaration.substringBefore(" = ").trim()
    }

    val parameterTypes =
        splitJavaParameters(declaration.substring(open + 1, close)).map { parameter ->
          parameter.trim().substringBeforeLast(' ', parameter.trim())
        }
    return declaration.substring(0, open + 1) +
        parameterTypes.joinToString(",") +
        declaration.substring(close)
  }

  private fun splitJavaParameters(parameters: String): List<String> {
    if (parameters.isBlank()) return emptyList()
    val result = mutableListOf<String>()
    var genericDepth = 0
    var start = 0
    for (index in parameters.indices) {
      when (parameters[index]) {
        '<' -> genericDepth++
        '>' -> genericDepth--
        ',' ->
            if (genericDepth == 0) {
              result += parameters.substring(start, index)
              start = index + 1
            }
      }
    }
    result += parameters.substring(start)
    return result
  }

  private fun assertContains(actual: String, expected: String) {
    assertTrue("Expected generated stub to contain: $expected\nStub:\n$actual", actual.contains(expected))
  }
}

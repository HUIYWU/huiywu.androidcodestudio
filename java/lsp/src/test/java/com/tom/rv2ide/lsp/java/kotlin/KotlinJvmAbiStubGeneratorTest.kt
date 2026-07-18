package com.tom.rv2ide.lsp.java.kotlin

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
    assertContains(classStub, "public static int VERSION;")
    assertContains(classStub, "public String ordinary()")
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
        open class Base

        class User<T>(val id: T) : Base(), Named, Tagged<T>
        interface Detailed : Named, Tagged<String>
        """.trimIndent()

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

  private fun assertContains(actual: String, expected: String) {
    assertTrue("Expected generated stub to contain: $expected\nStub:\n$actual", actual.contains(expected))
  }
}

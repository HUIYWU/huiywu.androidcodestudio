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
    assertContains(classStub, "public static Object create(String name)")
    assertContains(classStub, "public static int VERSION;")
    assertContains(classStub, "public String ordinary()")
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

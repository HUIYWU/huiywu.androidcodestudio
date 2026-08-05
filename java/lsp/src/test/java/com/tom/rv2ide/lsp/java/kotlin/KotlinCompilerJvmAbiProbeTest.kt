package com.tom.rv2ide.lsp.java.kotlin

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.Opcodes

/**
 * Golden evidence for Kotlin/JVM value-class constructor representations.
 *
 * These assertions intentionally describe compiler output only. They do not yet authorize the
 * source projector to expose any of these constructors to Java LSP consumers.
 */
class KotlinCompilerJvmAbiProbeTest {

  @Test
  fun valueClassConstructorParameters_preserveCompilerBoxingAndArrayRepresentations() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      class Direct(val id: UserId)
      class Nullable(val id: UserId?)
      class ValueClassArray(val ids: Array<UserId>)
      """.trimIndent(),
      "ValueClassConstructors.kt",
    ).associateBy { it.internalName }

    assertConstructorDescriptor(surfaces, "evidence/Direct", "(Ljava/lang/String;)V")
    // A nullable value class whose non-null underlying representation is already a reference can
    // encode null directly with that reference; Kotlin 2.1.0 does not box it as UserId here.
    assertConstructorDescriptor(surfaces, "evidence/Nullable", "(Ljava/lang/String;)V")
    assertConstructorDescriptor(surfaces, "evidence/ValueClassArray", "([Levidence/UserId;)V")

    // Direct unboxed value-class parameters need a compiler-generated marker overload. Record it
    // separately instead of mistaking it for an additional Java-source constructor surface.
    for (owner in listOf("evidence/Direct", "evidence/Nullable")) {
      val constructors = surfaces.getValue(owner).constructors()
      val representation = constructors.singleOrNull {
        it.descriptor == "(Ljava/lang/String;)V"
      }
      assertNotNull("Expected underlying representation constructor for $owner: $constructors", representation)
      assertTrue(
        "Expected underlying representation constructor to be private for $owner: $representation",
        representation!!.access and Opcodes.ACC_PRIVATE != 0,
      )

      val marker = constructors.singleOrNull {
        it.descriptor ==
          "(Ljava/lang/String;Lkotlin/jvm/internal/DefaultConstructorMarker;)V"
      }
      assertNotNull("Expected compiler marker constructor for $owner: $constructors", marker)
      assertTrue(
        "Expected marker constructor to be public for $owner: $marker",
        marker!!.access and Opcodes.ACC_PUBLIC != 0,
      )
      assertTrue(
        "Expected marker constructor to be synthetic for $owner: $marker",
        marker.access and Opcodes.ACC_SYNTHETIC != 0,
      )
    }
  }

  @Test
  fun valueClassArrayConstructor_isAJavaVisibleBoxedArraySurface() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      class ValueClassArray(val ids: Array<UserId>)
      """.trimIndent(),
      "ValueClassArrayConstructor.kt",
    ).associateBy { it.internalName }

    val constructors = surfaces.getValue("evidence/ValueClassArray").constructors()
    val arrayConstructor = constructors.singleOrNull {
      it.descriptor == "([Levidence/UserId;)V"
    }
    assertNotNull("Expected boxed value-class array constructor: $constructors", arrayConstructor)
    assertTrue(
      "Expected boxed value-class array constructor to be public: $arrayConstructor",
      arrayConstructor!!.access and Opcodes.ACC_PUBLIC != 0,
    )
    assertTrue(
      "Expected boxed value-class array constructor not to be synthetic: $arrayConstructor",
      arrayConstructor.access and Opcodes.ACC_SYNTHETIC == 0,
    )
  }

  @Test
  fun primitiveValueClassConstructorParameters_distinguishDirectAndNullableRepresentations() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class IntId(val raw: Int)

      class DirectIntId(val id: IntId)
      class NullableIntId(val id: IntId?)
      class IntIdArray(val ids: Array<IntId>)
      """.trimIndent(),
      "PrimitiveValueClassConstructors.kt",
    ).associateBy { it.internalName }

    assertConstructorDescriptor(surfaces, "evidence/DirectIntId", "(I)V")
    assertConstructorDescriptor(surfaces, "evidence/NullableIntId", "(Levidence/IntId;)V")
    assertConstructorDescriptor(surfaces, "evidence/IntIdArray", "([Levidence/IntId;)V")

    assertPrivateRepresentationAndPublicSyntheticMarker(
      surfaces.getValue("evidence/DirectIntId").constructors(),
      "(I)V",
      "(ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
    )
    assertPrivateRepresentationAndPublicSyntheticMarker(
      surfaces.getValue("evidence/NullableIntId").constructors(),
      "(Levidence/IntId;)V",
      "(Levidence/IntId;Lkotlin/jvm/internal/DefaultConstructorMarker;)V",
    )
  }

  @Test
  fun secondaryConstructorWithValueClassParameter_usesTheSameMarkerVisibilityBoundary() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      class Secondary private constructor() {
        constructor(id: UserId) : this()
      }
      """.trimIndent(),
      "SecondaryValueClassConstructor.kt",
    ).associateBy { it.internalName }

    assertPrivateRepresentationAndPublicSyntheticMarker(
      surfaces.getValue("evidence/Secondary").constructors(),
      "(Ljava/lang/String;)V",
      "(Ljava/lang/String;Lkotlin/jvm/internal/DefaultConstructorMarker;)V",
    )
  }

  @Test
  fun mixedAndMultipleDirectValueClassParameters_doNotCreateJavaVisibleConstructors() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      @JvmInline
      value class IntId(val raw: Int)

      class Mixed(val id: UserId, val count: Int)
      class Multiple(val first: UserId, val second: IntId)
      """.trimIndent(),
      "MixedValueClassConstructors.kt",
    ).associateBy { it.internalName }

    assertNoPublicNonSyntheticConstructor(
      surfaces.getValue("evidence/Mixed").constructors(),
      "evidence/Mixed",
    )
    assertNoPublicNonSyntheticConstructor(
      surfaces.getValue("evidence/Multiple").constructors(),
      "evidence/Multiple",
    )
    assertPrivateConstructorDescriptor(
      surfaces.getValue("evidence/Mixed").constructors(),
      "(Ljava/lang/String;I)V",
    )
    assertPrivateConstructorDescriptor(
      surfaces.getValue("evidence/Multiple").constructors(),
      "(Ljava/lang/String;I)V",
    )
  }

  @Test
  fun defaultedDirectValueClassParameter_doesNotCreateJavaVisibleJvmOverloads() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      class Defaulted @JvmOverloads constructor(
        val id: UserId,
        val count: Int = 0,
      )
      """.trimIndent(),
      "DefaultedValueClassConstructor.kt",
    ).associateBy { it.internalName }

    val constructors = surfaces.getValue("evidence/Defaulted").constructors()
    assertPrivateConstructorDescriptor(constructors, "(Ljava/lang/String;I)V")
    assertNoPublicNonSyntheticConstructor(constructors, "evidence/Defaulted")
    assertTrue(
      "Expected compiler-generated synthetic constructor machinery: $constructors",
      constructors.any { it.access and Opcodes.ACC_SYNTHETIC != 0 },
    )
  }

  @Test
  fun jvmOverloadsWithValueClassArray_createsNormalJavaVisibleVariants() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      class ArrayOverloaded @JvmOverloads constructor(
        val ids: Array<UserId>,
        val count: Int = 0,
      )
      """.trimIndent(),
      "ValueClassArrayOverloads.kt",
    ).associateBy { it.internalName }

    val constructors = surfaces.getValue("evidence/ArrayOverloaded").constructors()
    assertPublicNonSyntheticConstructor(constructors, "([Levidence/UserId;I)V")
    assertPublicNonSyntheticConstructor(constructors, "([Levidence/UserId;)V")
  }

  @Test
  fun constructorPropertyAccessors_distinguishMangledScalarsFromBoxedContainers() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      @JvmInline
      value class IntId(val raw: Int)

      class ReferenceScalar(var id: UserId)
      class NullableReferenceScalar(var id: UserId?)
      class PrimitiveScalar(var id: IntId)
      class NullablePrimitiveScalar(var id: IntId?)
      class ArrayProperty(var ids: Array<UserId>)
      class GenericProperty(var ids: List<UserId>)
      """.trimIndent(),
      "ValueClassConstructorProperties.kt",
    ).associateBy { it.internalName }

    assertMangledAccessor(
      surfaces.getValue("evidence/ReferenceScalar"),
      "getId-", "()Ljava/lang/String;",
    )
    assertMangledAccessor(
      surfaces.getValue("evidence/ReferenceScalar"),
      "setId-", "(Ljava/lang/String;)V",
    )
    assertMangledAccessor(
      surfaces.getValue("evidence/NullableReferenceScalar"),
      "getId-", "()Ljava/lang/String;",
    )
    assertMangledAccessor(
      surfaces.getValue("evidence/NullableReferenceScalar"),
      "setId-", "(Ljava/lang/String;)V",
    )
    assertMangledAccessor(
      surfaces.getValue("evidence/PrimitiveScalar"),
      "getId-", "()I",
    )
    assertMangledAccessor(
      surfaces.getValue("evidence/PrimitiveScalar"),
      "setId-", "(I)V",
    )
    assertMangledAccessor(
      surfaces.getValue("evidence/NullablePrimitiveScalar"),
      "getId-", "()Levidence/IntId;",
    )
    assertMangledAccessor(
      surfaces.getValue("evidence/NullablePrimitiveScalar"),
      "setId-", "(Levidence/IntId;)V",
    )

    assertPlainAccessor(
      surfaces.getValue("evidence/ArrayProperty"),
      "getIds", "()[Levidence/UserId;",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/ArrayProperty"),
      "setIds", "([Levidence/UserId;)V",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/GenericProperty"),
      "getIds", "()Ljava/util/List;",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/GenericProperty"),
      "setIds", "(Ljava/util/List;)V",
    )
  }

  @Test
  fun memberPropertyAccessors_requireExplicitJvmNamesForScalarValueClasses() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      class MemberScalar {
        var id: UserId = UserId("member")
      }

      class NamedScalar {
        @get:JvmName("readId")
        @set:JvmName("writeId")
        var id: UserId = UserId("named")
      }

      class ArrayMember {
        var ids: Array<UserId> = emptyArray()
      }

      class GenericMember {
        var ids: List<UserId> = emptyList()
      }
      """.trimIndent(),
      "ValueClassMemberProperties.kt",
    ).associateBy { it.internalName }

    assertMangledAccessor(
      surfaces.getValue("evidence/MemberScalar"),
      "getId-", "()Ljava/lang/String;",
    )
    assertMangledAccessor(
      surfaces.getValue("evidence/MemberScalar"),
      "setId-", "(Ljava/lang/String;)V",
    )

    assertPlainAccessor(
      surfaces.getValue("evidence/NamedScalar"),
      "readId", "()Ljava/lang/String;",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/NamedScalar"),
      "writeId", "(Ljava/lang/String;)V",
    )
    assertTrue(
      "Explicit @JvmName accessors must replace mangled defaults: ${surfaces.getValue("evidence/NamedScalar").members}",
      surfaces.getValue("evidence/NamedScalar").members.none {
        it.name.startsWith("getId-") || it.name.startsWith("setId-")
      },
    )

    assertPlainAccessor(
      surfaces.getValue("evidence/ArrayMember"),
      "getIds", "()[Levidence/UserId;",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/ArrayMember"),
      "setIds", "([Levidence/UserId;)V",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/GenericMember"),
      "getIds", "()Ljava/util/List;",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/GenericMember"),
      "setIds", "(Ljava/util/List;)V",
    )
  }

  @Test
  fun companionValueClassProperties_distinguishJvmFieldAndJvmStaticSurfaces() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      class FieldHost {
        companion object {
          @JvmField
          val ids: Array<UserId> = emptyArray()
        }
      }

      class StaticScalarHost {
        companion object {
          @JvmStatic
          var id: UserId = UserId("static")
        }
      }

      class NamedStaticScalarHost {
        companion object {
          @get:JvmName("readId")
          @set:JvmName("writeId")
          @JvmStatic
          var id: UserId = UserId("named")
        }
      }
      """.trimIndent(),
      "ValueClassCompanionProperties.kt",
    ).associateBy { it.internalName }

    val fieldHost = surfaces.getValue("evidence/FieldHost")
    val field = fieldHost.fieldsNamed("ids").singleOrNull { it.descriptor == "[Levidence/UserId;" }
    assertNotNull("Expected @JvmField boxed array field; actual=${fieldHost.fields}", field)
    assertTrue("Expected @JvmField to be public: $field", field!!.access and Opcodes.ACC_PUBLIC != 0)
    assertTrue("Expected @JvmField to be static: $field", field.access and Opcodes.ACC_STATIC != 0)
    assertTrue("Expected @JvmField not to be synthetic: $field", field.access and Opcodes.ACC_SYNTHETIC == 0)
    assertTrue("@JvmField must not create a host getter: ${fieldHost.members}",
      fieldHost.methodsNamed("getIds").isEmpty())
    assertTrue("@JvmField must not create a host setter: ${fieldHost.members}",
      fieldHost.methodsNamed("setIds").isEmpty())

    for (owner in listOf(
      "evidence/StaticScalarHost",
      "evidence/StaticScalarHost\$Companion",
    )) {
      assertMangledAccessor(
        surfaces.getValue(owner), "getId-", "()Ljava/lang/String;",
      )
      assertMangledAccessor(
        surfaces.getValue(owner), "setId-", "(Ljava/lang/String;)V",
      )
    }

    assertStaticPlainAccessor(
      surfaces.getValue("evidence/NamedStaticScalarHost"),
      "readId", "()Ljava/lang/String;",
    )
    assertStaticPlainAccessor(
      surfaces.getValue("evidence/NamedStaticScalarHost"),
      "writeId", "(Ljava/lang/String;)V",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/NamedStaticScalarHost\$Companion"),
      "readId", "()Ljava/lang/String;",
    )
    assertPlainAccessor(
      surfaces.getValue("evidence/NamedStaticScalarHost\$Companion"),
      "writeId", "(Ljava/lang/String;)V",
    )
  }

  @Test
  fun valueClassExtensionProperties_preserveCompilerManglingBoundaries() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      var UserId.label: String
        get() = raw
        set(value) {}

      var String.id: UserId
        get() = UserId(this)
        set(value) {}

      var UserId.other: UserId
        get() = this
        set(value) {}

      @get:JvmName("readLabel")
      @set:JvmName("writeLabel")
      var UserId.namedLabel: String
        get() = raw
        set(value) {}
      """.trimIndent(),
      "ValueClassFieldsAndExtensionProperties.kt",
    ).associateBy { it.internalName }

    val facade = surfaces.getValue("evidence/ValueClassFieldsAndExtensionPropertiesKt")
    assertMangledAccessor(facade, "getLabel-", "(Ljava/lang/String;)Ljava/lang/String;")
    assertMangledAccessor(facade, "setLabel-", "(Ljava/lang/String;Ljava/lang/String;)V")
    // A value-class return alone does not force a JVM name mangling. The getter can use the
    // underlying String return, while the setter takes a value-class parameter and is mangled.
    assertStaticPlainAccessor(facade, "getId", "(Ljava/lang/String;)Ljava/lang/String;")
    assertMangledAccessor(facade, "setId-", "(Ljava/lang/String;Ljava/lang/String;)V")
    assertMangledAccessor(facade, "getOther-", "(Ljava/lang/String;)Ljava/lang/String;")
    assertMangledAccessor(facade, "setOther-", "(Ljava/lang/String;Ljava/lang/String;)V")

    assertStaticPlainAccessor(facade, "readLabel", "(Ljava/lang/String;)Ljava/lang/String;")
    assertStaticPlainAccessor(facade, "writeLabel", "(Ljava/lang/String;Ljava/lang/String;)V")
    assertTrue("Explicit extension accessor names must replace mangled defaults: ${facade.members}",
      facade.members.none {
        it.name.startsWith("getNamedLabel-") || it.name.startsWith("setNamedLabel-")
      })
  }

  @Test
  fun interfaceValueClassExtensionProperties_preserveDefaultAccessorManglingBoundaries() {
    val surfaces = KotlinCompilerJvmAbiProbe.compile(
      """
      package evidence

      @JvmInline
      value class UserId(val raw: String)

      interface ValueClassExtensionContract {
        var UserId.label: String
        var String.id: UserId
        var UserId.other: UserId
      }
      """.trimIndent(),
      "InterfaceValueClassExtensionProperties.kt",
    ).associateBy { it.internalName }

    val contract = surfaces.getValue("evidence/ValueClassExtensionContract")
    assertMangledAccessor(contract, "getLabel-", "(Ljava/lang/String;)Ljava/lang/String;")
    assertMangledAccessor(contract, "setLabel-", "(Ljava/lang/String;Ljava/lang/String;)V")
    assertMangledAccessor(contract, "getId-", "(Ljava/lang/String;)Ljava/lang/String;")
    assertMangledAccessor(contract, "setId-", "(Ljava/lang/String;Ljava/lang/String;)V")
    assertMangledAccessor(contract, "getOther-", "(Ljava/lang/String;)Ljava/lang/String;")
    assertMangledAccessor(contract, "setOther-", "(Ljava/lang/String;Ljava/lang/String;)V")
  }

  private fun assertMangledAccessor(
    surface: KotlinCompilerJvmAbiProbe.ClassSurface,
    namePrefix: String,
    descriptor: String,
  ) {
    val matches = surface.members.filter {
      it.name.startsWith(namePrefix) && it.descriptor == descriptor
    }
    assertTrue(
      "Expected one mangled accessor $namePrefix*$descriptor in ${surface.internalName}; actual=${surface.members}",
      matches.size == 1,
    )
    val accessor = matches.single()
    assertTrue("Expected mangled accessor to be public: $accessor",
      accessor.access and Opcodes.ACC_PUBLIC != 0)
    assertTrue("Expected mangled accessor not to be synthetic: $accessor",
      accessor.access and Opcodes.ACC_SYNTHETIC == 0)
    val plainName = namePrefix.removeSuffix("-")
    assertTrue(
      "Plain accessor $plainName must not coexist with mangled value-class accessor: ${surface.members}",
      surface.methodsNamed(plainName).isEmpty(),
    )
  }

  private fun assertStaticPlainAccessor(
    surface: KotlinCompilerJvmAbiProbe.ClassSurface,
    name: String,
    descriptor: String,
  ) {
    val accessor = surface.methodsNamed(name).singleOrNull { it.descriptor == descriptor }
    assertNotNull("Expected static accessor $name$descriptor in ${surface.internalName}: ${surface.members}", accessor)
    assertTrue("Expected static accessor to be public: $accessor",
      accessor!!.access and Opcodes.ACC_PUBLIC != 0)
    assertTrue("Expected static accessor to be static: $accessor",
      accessor.access and Opcodes.ACC_STATIC != 0)
    assertTrue("Expected static accessor not to be synthetic: $accessor",
      accessor.access and Opcodes.ACC_SYNTHETIC == 0)
  }

  private fun assertPlainAccessor(
    surface: KotlinCompilerJvmAbiProbe.ClassSurface,
    name: String,
    descriptor: String,
  ) {
    val accessor = surface.methodsNamed(name).singleOrNull { it.descriptor == descriptor }
    assertNotNull("Expected plain accessor $name$descriptor in ${surface.internalName}: ${surface.members}", accessor)
    assertTrue("Expected plain accessor to be public: $accessor",
      accessor!!.access and Opcodes.ACC_PUBLIC != 0)
    assertTrue("Expected plain accessor not to be synthetic: $accessor",
      accessor.access and Opcodes.ACC_SYNTHETIC == 0)
  }

  private fun assertNoPublicNonSyntheticConstructor(
    constructors: List<KotlinCompilerJvmAbiProbe.Member>,
    owner: String,
  ) {
    val javaVisible = constructors.filter {
      it.access and Opcodes.ACC_PUBLIC != 0 && it.access and Opcodes.ACC_SYNTHETIC == 0
    }
    assertTrue(
      "Expected no public non-synthetic Java constructor for $owner; actual=$constructors",
      javaVisible.isEmpty(),
    )
  }

  private fun assertPrivateConstructorDescriptor(
    constructors: List<KotlinCompilerJvmAbiProbe.Member>,
    descriptor: String,
  ) {
    val constructor = constructors.singleOrNull { it.descriptor == descriptor }
    assertNotNull("Expected constructor $descriptor: $constructors", constructor)
    assertTrue(
      "Expected constructor $descriptor to be private: $constructor",
      constructor!!.access and Opcodes.ACC_PRIVATE != 0,
    )
  }

  private fun assertPublicNonSyntheticConstructor(
    constructors: List<KotlinCompilerJvmAbiProbe.Member>,
    descriptor: String,
  ) {
    val constructor = constructors.singleOrNull { it.descriptor == descriptor }
    assertNotNull("Expected constructor $descriptor: $constructors", constructor)
    assertTrue(
      "Expected constructor $descriptor to be public: $constructor",
      constructor!!.access and Opcodes.ACC_PUBLIC != 0,
    )
    assertTrue(
      "Expected constructor $descriptor not to be synthetic: $constructor",
      constructor.access and Opcodes.ACC_SYNTHETIC == 0,
    )
  }

  private fun assertPrivateRepresentationAndPublicSyntheticMarker(
    constructors: List<KotlinCompilerJvmAbiProbe.Member>,
    representationDescriptor: String,
    markerDescriptor: String,
  ) {
    val representation = constructors.singleOrNull { it.descriptor == representationDescriptor }
    assertNotNull(
      "Expected representation constructor $representationDescriptor: $constructors",
      representation,
    )
    assertTrue(
      "Expected representation constructor to be private: $representation",
      representation!!.access and Opcodes.ACC_PRIVATE != 0,
    )

    val marker = constructors.singleOrNull { it.descriptor == markerDescriptor }
    assertNotNull("Expected marker constructor $markerDescriptor: $constructors", marker)
    assertTrue(
      "Expected marker constructor to be public: $marker",
      marker!!.access and Opcodes.ACC_PUBLIC != 0,
    )
    assertTrue(
      "Expected marker constructor to be synthetic: $marker",
      marker.access and Opcodes.ACC_SYNTHETIC != 0,
    )
  }

  private fun assertConstructorDescriptor(
    surfaces: Map<String, KotlinCompilerJvmAbiProbe.ClassSurface>,
    owner: String,
    descriptor: String,
  ) {
    val surface = surfaces[owner]
    assertNotNull("Compiler probe did not emit $owner; emitted=${surfaces.keys}", surface)
    val constructors = surface!!.constructors()
    assertTrue(
      "Expected constructor ABI $descriptor for $owner; actual=$constructors",
      constructors.any { it.descriptor == descriptor },
    )
  }
}
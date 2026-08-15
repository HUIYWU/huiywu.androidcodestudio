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
 *  along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.xml.internal.resources

import com.android.aaptcompiler.AaptResourceType.DRAWABLE
import com.android.aaptcompiler.AaptResourceType.STRING
import com.android.aaptcompiler.AaptResourceType.STYLE
import com.android.aaptcompiler.extractPathData
import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.xml.resources.ResourceTableFileInput
import com.tom.rv2ide.xml.resources.ResourceTableInputSnapshot
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test

class DefaultResourceTableRegistryTest {

  private lateinit var root: File
  private lateinit var registry: DefaultResourceTableRegistry

  @Before
  fun setUp() {
    root = Files.createTempDirectory("resource-table-registry").toFile()
    registry = DefaultResourceTableRegistry().apply { isLoggingEnabled = false }
  }

  @After
  fun tearDown() {
    registry.clear()
    root.deleteRecursively()
  }

  @Test
  fun buildsStyleEntriesFromValuesXml() {
    val resDir = File(root, "res").apply { mkdirs() }
    val valuesDir = File(resDir, "values").apply { mkdirs() }
    File(valuesDir, "styles.xml").writeText(
      """
      <resources>
        <style name="TextAppearance.Material3.BodyMedium"
            parent="TextAppearance.M3.Sys.Typescale.BodyMedium" />
      </resources>
      """.trimIndent(),
    )

    val table = registry.forPackage("com.google.android.material", resDir)

    assertThat(table).isNotNull()
    assertThat(table!!.findPackage("com.google.android.material")?.findGroup(STYLE)
      ?.findEntry("TextAppearance.Material3.BodyMedium"))
      .isNotNull()
  }

  @Test
  fun buildsFileResourceVariantsWithDirectoryConfigurations() {
    val resDir = File(root, "res").apply { mkdirs() }
    val drawable = File(resDir, "drawable").apply { mkdirs() }
    val drawableNight = File(resDir, "drawable-night").apply { mkdirs() }
    val drawableV24 = File(resDir, "drawable-v24").apply { mkdirs() }
    val defaultFile = File(drawable, "logo.xml").apply { writeText("<vector />") }
    val nightFile = File(drawableNight, "logo.xml").apply { writeText("<vector />") }
    val v24File = File(drawableV24, "logo.xml").apply { writeText("<vector />") }

    val entry =
        registry.forPackage(PACKAGE_NAME, resDir)
            ?.findPackage(PACKAGE_NAME)
            ?.findGroup(DRAWABLE)
            ?.findEntry("logo")

    assertThat(entry).isNotNull()
    assertThat(entry!!.findValue(extractPathData(defaultFile).config)?.value?.source?.path)
        .isEqualTo(defaultFile.path)
    assertThat(entry.findValue(extractPathData(nightFile).config)?.value?.source?.path)
        .isEqualTo(nightFile.path)
    assertThat(entry.findValue(extractPathData(v24File).config)?.value?.source?.path)
        .isEqualTo(v24File.path)
  }

  @Test
  fun refreshPackageWithUnchangedResourcesReusesTableAndGeneration() {
    val resDir = File(root, "res").apply { mkdirs() }
    val first = registry.forPackage(PACKAGE_NAME, resDir)
    val firstGeneration = registry.getGeneration(PACKAGE_NAME)

    val result = registry.refreshPackage(PACKAGE_NAME, resDir)

    assertThat(result).isSameInstanceAs(first)
    assertThat(registry.getGeneration(PACKAGE_NAME)).isEqualTo(firstGeneration)
  }

  @Test
  fun refreshPackageReplacesPublishedTableOnlyAfterSuccessfulBuild() {
    val resDir = File(root, "res").apply { mkdirs() }
    val first = registry.forPackage(PACKAGE_NAME, resDir)
    File(resDir, "values.xml").writeText("<resources />")

    val replacement = registry.refreshPackage(PACKAGE_NAME, resDir)

    assertThat(first).isNotNull()
    assertThat(replacement).isNotNull()
    assertThat(replacement).isNotSameInstanceAs(first)
    assertThat(registry.getGeneration(PACKAGE_NAME)).isEqualTo(2L)
    assertThat(registry.forPackage(PACKAGE_NAME, resDir)).isSameInstanceAs(replacement)
  }

  @Test
  fun refreshPackageRetainsPublishedTableWhenReplacementCannotBeBuilt() {
    val resDir = File(root, "res").apply { mkdirs() }
    val first = registry.forPackage(PACKAGE_NAME, resDir)
    val firstGeneration = registry.getGeneration(PACKAGE_NAME)
    val missingResDir = File(root, "missing-res")

    val result = registry.refreshPackage(PACKAGE_NAME, missingResDir)

    assertThat(first).isNotNull()
    assertThat(result).isSameInstanceAs(first)
    assertThat(registry.getGeneration(PACKAGE_NAME)).isEqualTo(firstGeneration)
    assertThat(registry.forPackage(PACKAGE_NAME, resDir)).isSameInstanceAs(first)
  }

  @Test
  fun refreshPackageUsesMemoryInputAndItsRevisionForCaching() {
    val resDir = File(root, "res").apply { mkdirs() }
    val valuesDir = File(resDir, "values").apply { mkdirs() }
    val strings = File(valuesDir, "strings.xml").apply {
      writeText("<resources><string name=\"saved_title\">Saved</string></resources>")
    }
    val initial = registry.forPackage(PACKAGE_NAME, resDir)!!
    val memoryV1 =
        ResourceTableInputSnapshot.of(
            mapOf(
                strings.toPath() to
                    ResourceTableFileInput(
                        "<resources><string name=\"unsaved_title\">Unsaved</string></resources>",
                        revision = 1L,
                    )
            )
        )

    val replacement = registry.refreshPackage(PACKAGE_NAME, memoryV1, resDir)!!
    val generation = registry.getGeneration(PACKAGE_NAME)
    val reused = registry.refreshPackage(PACKAGE_NAME, memoryV1, resDir)
    val memoryV2 =
        ResourceTableInputSnapshot.of(
            mapOf(
                strings.toPath() to
                    ResourceTableFileInput(
                        "<resources><string name=\"newer_unsaved_title\">Newer</string></resources>",
                        revision = 2L,
                    )
            )
        )
    val newer = registry.refreshPackage(PACKAGE_NAME, memoryV2, resDir)!!

    assertThat(replacement).isNotSameInstanceAs(initial)
    assertThat(replacement.findPackage(PACKAGE_NAME)?.findGroup(STRING)?.findEntry("unsaved_title"))
        .isNotNull()
    assertThat(replacement.findPackage(PACKAGE_NAME)?.findGroup(STRING)?.findEntry("saved_title"))
        .isNull()
    assertThat(reused).isSameInstanceAs(replacement)
    assertThat(registry.getGeneration(PACKAGE_NAME)).isEqualTo(generation + 1L)
    assertThat(newer).isNotSameInstanceAs(replacement)
    assertThat(newer.findPackage(PACKAGE_NAME)?.findGroup(STRING)?.findEntry("newer_unsaved_title"))
        .isNotNull()
  }

  @Test
  fun refreshPackageWithoutMemoryInputFallsBackToDisk() {
    val resDir = File(root, "res").apply { mkdirs() }
    val valuesDir = File(resDir, "values").apply { mkdirs() }
    val strings = File(valuesDir, "strings.xml").apply {
      writeText("<resources><string name=\"saved_title\">Saved</string></resources>")
    }
    registry.forPackage(PACKAGE_NAME, resDir)
    val memory =
        ResourceTableInputSnapshot.of(
            mapOf(
                strings.toPath() to
                    ResourceTableFileInput(
                        "<resources><string name=\"unsaved_title\">Unsaved</string></resources>",
                        revision = 1L,
                    )
            )
        )
    val active = registry.refreshPackage(PACKAGE_NAME, memory, resDir)!!

    val disk = registry.refreshPackage(PACKAGE_NAME, ResourceTableInputSnapshot.EMPTY, resDir)!!

    assertThat(disk).isNotSameInstanceAs(active)
    assertThat(disk.findPackage(PACKAGE_NAME)?.findGroup(STRING)?.findEntry("saved_title"))
        .isNotNull()
    assertThat(disk.findPackage(PACKAGE_NAME)?.findGroup(STRING)?.findEntry("unsaved_title"))
        .isNull()
  }

  @Test
  fun invalidMemoryInputRetainsPublishedTableAndGeneration() {
    val resDir = File(root, "res").apply { mkdirs() }
    val valuesDir = File(resDir, "values").apply { mkdirs() }
    val strings = File(valuesDir, "strings.xml").apply {
      writeText("<resources><string name=\"saved_title\">Saved</string></resources>")
    }
    val initial = registry.forPackage(PACKAGE_NAME, resDir)!!
    val generation = registry.getGeneration(PACKAGE_NAME)
    val invalid =
        ResourceTableInputSnapshot.of(
            mapOf(
                strings.toPath() to ResourceTableFileInput("<not-resources />", revision = 1L)
            )
        )

    val result = registry.refreshPackage(PACKAGE_NAME, invalid, resDir)

    assertThat(result).isSameInstanceAs(initial)
    assertThat(registry.getGeneration(PACKAGE_NAME)).isEqualTo(generation)
    assertThat(registry.forPackage(PACKAGE_NAME, resDir)).isSameInstanceAs(initial)
  }

  @Test
  fun obsoleteMemoryRefreshRetainsPublishedTableAndGeneration() {
    val resDir = File(root, "res").apply { mkdirs() }
    val valuesDir = File(resDir, "values").apply { mkdirs() }
    val strings = File(valuesDir, "strings.xml").apply {
      writeText("<resources><string name=\"saved_title\">Saved</string></resources>")
    }
    val initial = registry.forPackage(PACKAGE_NAME, resDir)!!
    val generation = registry.getGeneration(PACKAGE_NAME)
    val inputs =
        ResourceTableInputSnapshot.of(
            mapOf(
                strings.toPath() to
                    ResourceTableFileInput(
                        "<resources><string name=\"unsaved_title\">Unsaved</string></resources>",
                        revision = 1L,
                    )
            )
        )

    val result = registry.refreshPackage(PACKAGE_NAME, inputs, { true }, resDir)

    assertThat(result).isSameInstanceAs(initial)
    assertThat(registry.getGeneration(PACKAGE_NAME)).isEqualTo(generation)
    assertThat(registry.forPackage(PACKAGE_NAME, resDir)).isSameInstanceAs(initial)
  }

  @Test
  fun removeTableAllowsPackageTableToBeCreatedAgain() {
    val resDir = File(root, "res").apply { mkdirs() }
    val first = registry.forPackage(PACKAGE_NAME, resDir)

    registry.removeTable(PACKAGE_NAME)
    assertThat(registry.getGeneration(PACKAGE_NAME)).isEqualTo(0L)
    val replacement = registry.forPackage(PACKAGE_NAME, resDir)

    assertThat(first).isNotNull()
    assertThat(replacement).isNotNull()
    assertThat(replacement).isNotSameInstanceAs(first)
  }

  private companion object {
    const val PACKAGE_NAME = "com.example.snapshot"
  }
}

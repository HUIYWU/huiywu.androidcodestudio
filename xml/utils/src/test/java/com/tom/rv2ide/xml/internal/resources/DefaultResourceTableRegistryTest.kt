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

import com.android.aaptcompiler.AaptResourceType.STYLE
import com.google.common.truth.Truth.assertThat
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

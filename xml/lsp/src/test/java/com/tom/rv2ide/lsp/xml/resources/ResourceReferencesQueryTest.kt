/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.resources

import com.google.common.truth.Truth.assertThat
import java.nio.file.Path
import java.nio.file.Paths
import junit.framework.TestCase

class ResourceReferencesQueryTest : TestCase() {

  fun testFindsOnlySameTypeAndNameAndHonorsIncludeDeclaration() {
    val definitions =
        definitions(
            "project/app/src/main/res/values/strings.xml" to
                "<resources><string name=\"title\">Title</string><color name=\"title\">#000</color></resources>"
        )
    val source = Paths.get("project/app/src/main/res/layout/screen.xml")
    val sourceText = "<View android:text=\"@string/title\" android:tag=\"@color/title\" />"
    val occurrences = mapOf(source to scan(sourceText))
    val target = occurrences.getValue(source).first { it.reference.text == "@string/title" }

    val withoutDeclarations = ResourceReferencesQuery.find(target, definitions, occurrences, false)
    val withDeclarations = ResourceReferencesQuery.find(target, definitions, occurrences, true)

    assertThat(withoutDeclarations).hasSize(1)
    assertThat(withoutDeclarations.single().file).isEqualTo(source)
    assertThat(withDeclarations).hasSize(2)
    assertThat(withDeclarations.map { it.file })
        .containsExactly(Paths.get("project/app/src/main/res/values/strings.xml"), source)
        .inOrder()
  }

  fun testKeepsAllQualifiedDeclarations() {
    val definitions =
        definitions(
            "project/app/src/main/res/values/strings.xml" to "<resources><string name=\"title\">Base</string></resources>",
            "project/app/src/main/res/values-v31/strings.xml" to "<resources><string name=\"title\">New</string></resources>",
        )
    val source = Paths.get("project/app/src/main/res/layout/screen.xml")
    val occurrences = mapOf(source to scan("<View android:text=\"@string/title\" />"))

    val locations = ResourceReferencesQuery.find(occurrences.getValue(source).single(), definitions, occurrences, true)

    assertThat(locations.map { it.file })
        .containsExactly(
            Paths.get("project/app/src/main/res/values/strings.xml"),
            Paths.get("project/app/src/main/res/values-v31/strings.xml"),
            source,
        )
        .inOrder()
  }
  fun testFindsLocalAttrAndThemeAttrReferences() {
    val attributes = Paths.get("project/app/src/main/res/values/attrs.xml")
    val layout = Paths.get("project/app/src/main/res/layout/screen.xml")
    val attributesText = "<resources><attr name=\"brand_color\" format=\"color\" /></resources>"
    val layoutText =
        "<View android:background=\"?attr/brand_color\" android:foreground=\"@attr/brand_color\" />"
    val definitions = definitions(attributes.toString() to attributesText)
    val occurrences = mapOf(layout to scan(layoutText))
    val themeTarget = occurrences.getValue(layout).first { it.reference.isThemeAttribute }
    val resourceTarget = occurrences.getValue(layout).first { !it.reference.isThemeAttribute }

    val themeLocations = ResourceReferencesQuery.find(themeTarget, definitions, occurrences, true)
    val resourceLocations = ResourceReferencesQuery.find(resourceTarget, definitions, occurrences, true)

    assertThat(themeLocations).isEqualTo(resourceLocations)
    assertThat(themeLocations.map { it.file }).containsExactly(attributes, layout, layout).inOrder()
  }

  fun testCreatingIdIsReturnedOnlyAsExactDeclarationWhenRequested() {

    val layout = Paths.get("project/app/src/main/res/layout/screen.xml")
    val other = Paths.get("project/app/src/main/res/layout/other.xml")
    val layoutText = "<View android:id=\"@+id/content\" />"
    val otherText = "<View android:layout_below=\"@id/content\" />"
    val definitions = definitions(layout.toString() to layoutText, other.toString() to otherText)
    val occurrences = mapOf(layout to scan(layoutText), other to scan(otherText))
    val target = occurrences.getValue(other).single()

    val withoutDeclarations = ResourceReferencesQuery.find(target, definitions, occurrences, false)
    val withDeclarations = ResourceReferencesQuery.find(target, definitions, occurrences, true)

    assertThat(withoutDeclarations.map { it.file }).containsExactly(other)
    assertThat(withDeclarations.map { it.file }).containsExactly(layout, other).inOrder()
    assertThat(withDeclarations.first().range.end.column - withDeclarations.first().range.start.column)
        .isEqualTo("content".length)
  }

  fun testRejectsTargetsWithoutWorkspaceDefinitionOrWithPackage() {
    val source = Paths.get("project/app/src/main/res/layout/screen.xml")
    val unresolved = mapOf(source to scan("<View android:text=\"@string/missing\" />"))
    val framework = mapOf(source to scan("<View android:text=\"@android:string/ok\" />"))

    assertThat(
            ResourceReferencesQuery.find(
                unresolved.getValue(source).single(),
                emptyList(),
                unresolved,
                true,
            )
        )
        .isEmpty()
    assertThat(
            ResourceReferencesQuery.find(
                framework.getValue(source).single(),
                definitions("project/app/src/main/res/values/strings.xml" to "<resources><string name=\"ok\">OK</string></resources>"),
                framework,
                true,
            )
        )
        .isEmpty()
  }

  private fun definitions(vararg files: Pair<String, String>): List<ResourceDefinition> {
    return files.flatMap { (path, text) ->
      val result = ResourceDefinitionExtractor.extract(Paths.get(path), text)
      (result as ResourceDefinitionExtractor.Extraction.Available).definitions
    }
  }

  private fun scan(text: String): List<ResourceReferenceOccurrence> {
    val result = ResourceReferenceScanner.scan(text)
    return (result as ResourceReferenceScanner.ScanResult.Available).occurrences
  }
}
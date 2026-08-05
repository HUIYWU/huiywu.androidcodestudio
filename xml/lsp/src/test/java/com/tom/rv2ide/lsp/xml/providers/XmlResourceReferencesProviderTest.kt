/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.providers

import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.lsp.models.ReferenceRole
import com.tom.rv2ide.lsp.xml.resources.ResourceFileEntry
import com.tom.rv2ide.lsp.xml.resources.ResourceReferenceScanner
import com.tom.rv2ide.lsp.xml.resources.ResourceSnapshot
import com.tom.rv2ide.lsp.xml.resources.snapshotEntries
import java.nio.file.Path
import java.nio.file.Paths
import junit.framework.TestCase

class XmlResourceReferencesProviderTest : TestCase() {

  fun testScansUsageOnlyResourceFilesFromSnapshot() {
    val definitionsFile = Paths.get("project/app/src/main/res/values/strings.xml")
    val firstUsage = Paths.get("project/app/src/main/res/layout/first.xml")
    val secondUsage = Paths.get("project/app/src/main/res/layout/second.xml")
    val texts =
        linkedMapOf(
            definitionsFile to "<resources><string name=\"title\">Title</string></resources>",
            firstUsage to "<View android:text=\"@string/title\" />",
            secondUsage to "<View android:contentDescription=\"@string/title\" />",
        )
    val snapshot = snapshot(texts)
    val target = scan(texts.getValue(firstUsage)).single()

    val locations =
        XmlResourceReferencesProvider().findInSnapshot(
            target = target,
            snapshot = snapshot,
            includeDeclaration = true,
        )

    assertThat(locations).isNotNull()
    assertThat(locations!!.map { it.file }).containsExactly(definitionsFile, firstUsage, secondUsage).inOrder()
  }

  fun testClassifiesDefinitionsAndUsagesInLocationOrder() {
    val definitionsFile = Paths.get("project/app/src/main/res/values/strings.xml")
    val firstUsage = Paths.get("project/app/src/main/res/layout/first.xml")
    val secondUsage = Paths.get("project/app/src/main/res/layout/second.xml")
    val texts =
        linkedMapOf(
            definitionsFile to "<resources><string name=\"title\">Title</string></resources>",
            firstUsage to "<View android:text=\"@string/title\" />",
            secondUsage to "<View android:contentDescription=\"@string/title\" />",
        )
    val snapshot = snapshot(texts)
    val target = scan(texts.getValue(firstUsage)).single()
    val provider = XmlResourceReferencesProvider()
    val locations =
        provider.findInSnapshot(target, snapshot, includeDeclaration = true)

    assertThat(provider.rolesFor(target, snapshot, locations))
        .containsExactly(ReferenceRole.DEFINITION, ReferenceRole.USAGE, ReferenceRole.USAGE)
        .inOrder()
  }

  fun testResolvesValuesAndIdDefinitionNamesAsTargets() {
    val values = Paths.get("project/app/src/main/res/values/references.xml")
    val text =
        """
        <resources>
            <string name="probe_title">Title</string>
            <item type="id" name="probe_id" />
        </resources>
        """
            .trimIndent()
    val snapshot = snapshot(linkedMapOf(values to text))
    val provider = XmlResourceReferencesProvider()

    val stringTarget = provider.targetFor(values, text, text.indexOf("probe_title") + 2, snapshot)
    val idTarget = provider.targetFor(values, text, text.indexOf("probe_id") + 2, snapshot)

    assertThat(stringTarget?.reference?.text).isEqualTo("@string/probe_title")
    assertThat(idTarget?.reference?.text).isEqualTo("@id/probe_id")
  }

  fun testFindsThemeAndResourceAttrUsagesFromAttrDefinition() {
    val attributes = Paths.get("project/app/src/main/res/values/attrs.xml")
    val layout = Paths.get("project/app/src/main/res/layout/screen.xml")
    val attributesText = "<resources><attr name=\"brand_color\" format=\"color\" /></resources>"
    val layoutText =
        "<View android:background=\"?attr/brand_color\" android:foreground=\"@attr/brand_color\" />"
    val snapshot = snapshot(linkedMapOf(attributes to attributesText, layout to layoutText))
    val provider = XmlResourceReferencesProvider()
    val target = provider.targetFor(attributes, attributesText, attributesText.indexOf("brand_color") + 2, snapshot)

    val resolvedTarget = checkNotNull(target)
    assertThat(resolvedTarget.reference.text).isEqualTo("@attr/brand_color")
    val locations = provider.findInSnapshot(resolvedTarget, snapshot, includeDeclaration = true)
    assertThat(locations.map { it.file }).containsExactly(attributes, layout, layout).inOrder()
    assertThat(provider.rolesFor(resolvedTarget, snapshot, locations))
        .containsExactly(ReferenceRole.DEFINITION, ReferenceRole.USAGE, ReferenceRole.USAGE)
        .inOrder()
  }

  fun testFindsResAutoAttributeUsageFromNestedStyleableAttrDefinition() {
    val attributes = Paths.get("project/app/src/main/res/values/attrs.xml")
    val layout = Paths.get("project/app/src/main/res/layout/screen.xml")
    val attributesText =
        "<resources><declare-styleable name=\"Widget\"><attr name=\"corner_radius\" format=\"dimension\" /></declare-styleable></resources>"
    val layoutText =
        "<View xmlns:custom=\"http://schemas.android.com/apk/res-auto\" custom:corner_radius=\"12dp\" />"
    val snapshot = snapshot(linkedMapOf(attributes to attributesText, layout to layoutText))
    val provider = XmlResourceReferencesProvider()
    val target =
        checkNotNull(provider.targetFor(attributes, attributesText, attributesText.indexOf("corner_radius") + 2, snapshot))

    val locations = provider.findInSnapshot(target, snapshot, includeDeclaration = true)
    assertThat(locations.map { it.file }).containsExactly(attributes, layout).inOrder()
    assertThat(provider.rolesFor(target, snapshot, locations))
        .containsExactly(ReferenceRole.DEFINITION, ReferenceRole.USAGE)
        .inOrder()
  }

  fun testResolvesFileDefinitionOnlyWhenCursorIsNotOnInnerReference() {
    val drawable = Paths.get("project/app/src/main/res/drawable/probe_background.xml")
    val values = Paths.get("project/app/src/main/res/values/colors.xml")
    val drawableText =
        """
        <shape xmlns:android="http://schemas.android.com/apk/res/android">
            <solid android:color="@color/probe_color" />
        </shape>
        """
            .trimIndent()
    val valuesText = "<resources><color name=\"probe_color\">#000000</color></resources>"
    val snapshot = snapshot(linkedMapOf(drawable to drawableText, values to valuesText))
    val provider = XmlResourceReferencesProvider()

    val fileTarget = provider.targetFor(drawable, drawableText, drawableText.indexOf("shape"), snapshot)
    val innerReferenceTarget =
        provider.targetFor(drawable, drawableText, drawableText.indexOf("probe_color") + 2, snapshot)

    assertThat(fileTarget?.reference?.text).isEqualTo("@drawable/probe_background")
    assertThat(innerReferenceTarget?.reference?.text).isEqualTo("@color/probe_color")
  }

  fun testFailsClosedWhenAnyIndexedFileCannotBuildAnEntry() {
    val definitionsFile = Paths.get("project/app/src/main/res/values/strings.xml")
    val usage = Paths.get("project/app/src/main/res/layout/screen.xml")
    val broken = Paths.get("project/app/src/main/res/layout/broken.xml")
    val entries =
        linkedMapOf(
            definitionsFile to ResourceFileEntry.create(definitionsFile, "<resources><string name=\"title\">Title</string></resources>"),
            usage to ResourceFileEntry.create(usage, "<View android:text=\"@string/title\" />"),
            broken to ResourceFileEntry.create(broken, "<View android:text=\"@string/title\">"),
        )

    assertThat(snapshotEntries(entries)).isEqualTo(ResourceSnapshot.Unavailable)
  }

  private fun snapshot(texts: Map<Path, String>): ResourceSnapshot.Available {
    val entries = texts.mapValues { (file, text) -> ResourceFileEntry.create(file, text) }
    return snapshotEntries(entries) as ResourceSnapshot.Available
  }

  private fun scan(text: String) =
      (ResourceReferenceScanner.scan(text) as ResourceReferenceScanner.ScanResult.Available).occurrences
}
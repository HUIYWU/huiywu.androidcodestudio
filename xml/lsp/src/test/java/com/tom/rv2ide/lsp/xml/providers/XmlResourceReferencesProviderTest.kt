/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.providers

import com.google.common.truth.Truth.assertThat
import com.tom.rv2ide.lsp.models.ReferenceRole
import com.tom.rv2ide.lsp.xml.resources.ResourceDefinitionExtractor
import com.tom.rv2ide.lsp.xml.resources.ResourceReferenceScanner
import com.tom.rv2ide.lsp.xml.resources.ResourceSnapshot
import com.tom.rv2ide.lsp.xml.resources.snapshotDefinitions
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
            readText = texts::getValue,
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
        provider.findInSnapshot(target, snapshot, includeDeclaration = true, readText = texts::getValue)!!

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

  fun testFailsClosedWhenAnyIndexedFileCannotBeScanned() {
    val definitionsFile = Paths.get("project/app/src/main/res/values/strings.xml")
    val usage = Paths.get("project/app/src/main/res/layout/screen.xml")
    val broken = Paths.get("project/app/src/main/res/layout/broken.xml")
    val texts =
        linkedMapOf(
            definitionsFile to "<resources><string name=\"title\">Title</string></resources>",
            usage to "<View android:text=\"@string/title\" />",
            broken to "<View android:text=\"@string/title\">",
        )
    val snapshot = snapshot(texts)
    val target = scan(texts.getValue(usage)).single()

    assertThat(
            XmlResourceReferencesProvider().findInSnapshot(
                target = target,
                snapshot = snapshot,
                includeDeclaration = true,
                readText = texts::getValue,
            )
        )
        .isNull()
  }

  private fun snapshot(texts: Map<Path, String>): ResourceSnapshot.Available {
    val extractions = texts.mapValues { (file, text) -> ResourceDefinitionExtractor.extract(file, text) }
    return snapshotDefinitions(extractions) as ResourceSnapshot.Available
  }

  private fun scan(text: String) =
      (ResourceReferenceScanner.scan(text) as ResourceReferenceScanner.ScanResult.Available).occurrences
}
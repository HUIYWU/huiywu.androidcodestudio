/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.providers

import com.google.common.truth.Truth.assertThat
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
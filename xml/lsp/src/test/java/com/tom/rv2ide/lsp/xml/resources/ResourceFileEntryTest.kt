/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.resources

import com.android.aaptcompiler.AaptResourceType.COLOR
import com.android.aaptcompiler.AaptResourceType.DRAWABLE
import com.android.aaptcompiler.AaptResourceType.ID
import com.android.aaptcompiler.AaptResourceType.STRING
import com.google.common.truth.Truth.assertThat
import java.nio.file.Paths
import junit.framework.TestCase

class ResourceFileEntryTest : TestCase() {

  fun testCombinesValuesDefinitionsAndOccurrencesFromOneText() {
    val entry =
        ResourceFileEntry.create(
            Paths.get("project/app/src/main/res/values/references.xml"),
            "<resources><string name=\"title\">@string/other</string><color name=\"accent\">#000</color></resources>",
        )

    assertThat(entry).isInstanceOf(ResourceFileEntry.Available::class.java)
    entry as ResourceFileEntry.Available
    assertThat(entry.definitions.map { it.type to it.name })
        .containsExactly(STRING to "title", COLOR to "accent")
        .inOrder()
    assertThat(entry.occurrences.map { it.reference.type to it.reference.entry })
        .containsExactly(STRING to "other")
  }

  fun testCombinesFileResourceAndCreatingIdDeclaration() {
    val entry =
        ResourceFileEntry.create(
            Paths.get("project/app/src/main/res/drawable/probe.xml"),
            "<View android:id=\"@+id/probe\" android:background=\"@color/accent\" />",
        )

    assertThat(entry).isInstanceOf(ResourceFileEntry.Available::class.java)
    entry as ResourceFileEntry.Available
    assertThat(entry.definitions.map { it.type to it.name })
        .containsExactly(DRAWABLE to "probe", ID to "probe")
        .inOrder()
    assertThat(entry.occurrences.map { it.reference.type to it.reference.entry })
        .containsExactly(ID to "probe", COLOR to "accent")
        .inOrder()
    assertThat(entry.occurrences.first().isCreatingId).isTrue()
  }

  fun testFailsClosedWhenScannerCannotReliablyReadDocument() {
    val entry =
        ResourceFileEntry.create(
            Paths.get("project/app/src/main/res/layout/broken.xml"),
            "<View android:text=\"@string/title\">",
        )

    assertThat(entry).isEqualTo(ResourceFileEntry.Unavailable)
  }
}

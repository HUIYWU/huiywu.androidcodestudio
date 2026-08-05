/*
 *  This file is part of AndroidCodeStudio.
 */
package com.tom.rv2ide.lsp.xml.resources

import com.google.common.truth.Truth.assertThat
import java.nio.file.Path
import java.nio.file.Paths
import junit.framework.TestCase

class ModuleResourceIndexTest : TestCase() {

  override fun tearDown() {
    ModuleResourceIndex.clear()
    super.tearDown()
  }

  fun testStatsCountsAvailableEntriesAcrossModules() {
    val values = Paths.get("project/app/src/main/res/values/strings.xml")
    val layout = Paths.get("project/app/src/main/res/layout/screen.xml")
    val broken = Paths.get("project/feature/src/main/res/values/broken.xml")
    val valuesEntry =
        ResourceFileEntry.create(values, "<resources><string name=\"title\">Title</string></resources>")
    val layoutEntry =
        ResourceFileEntry.create(
            layout,
            "<View xmlns:android=\"http://schemas.android.com/apk/res/android\" android:id=\"@+id/content\" android:text=\"@string/title\" />",
        )

    val stats =
        ModuleResourceIndex.statsFor(
            listOf(
                linkedMapOf(values to valuesEntry, layout to layoutEntry),
                mapOf(broken to ResourceFileEntry.Unavailable),
            )
        )

    assertThat(stats.moduleCount).isEqualTo(2)
    assertThat(stats.fileCount).isEqualTo(3)
    assertThat(stats.definitionCount).isEqualTo(3)
    assertThat(stats.occurrenceCount).isEqualTo(2)
  }

  fun testDocumentOverlaysReplaceIndexedEntriesOnlyInsideResourceDirectories() {
    val resourceDirectory = Paths.get("project/app/src/main/res")
    val values = resourceDirectory.resolve("values/strings.xml")
    val outside = Paths.get("project/other/values/strings.xml")
    val entries =
        mutableMapOf<Path, ResourceFileEntry>(
            values to ResourceFileEntry.create(values, "<resources><string name=\"disk_title\">Disk</string></resources>")
        )

    ModuleResourceIndex.applyDocumentOverlays(
        entries,
        setOf(resourceDirectory),
        mapOf(
            values to "<resources><string name=\"unsaved_title\">Unsaved</string></resources>",
            outside to "<resources><string name=\"outside\">Outside</string></resources>",
        ),
    )

    val snapshot = snapshotEntries(entries) as ResourceSnapshot.Available
    assertThat(snapshot.definitions.map { it.name }).containsExactly("unsaved_title")
    assertThat(snapshot.files).containsExactly(values)
  }

  fun testStatsForEmptyCacheAndClearAreZero() {
    ModuleResourceIndex.clear()

    assertThat(ModuleResourceIndex.statsFor(emptyList()))
        .isEqualTo(ModuleResourceIndex.CacheStats(0, 0, 0, 0))
    assertThat(ModuleResourceIndex.stats())
        .isEqualTo(ModuleResourceIndex.CacheStats(0, 0, 0, 0))
  }
}

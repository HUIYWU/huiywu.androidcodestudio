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
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.lsp.xml.providers.completion

import com.android.SdkConstants.ANDROID_URI
import com.android.aapt.Resources.Attribute.FormatFlags
import com.android.aapt.Resources.Attribute.FormatFlags.BOOLEAN
import com.android.aapt.Resources.Attribute.FormatFlags.COLOR
import com.android.aapt.Resources.Attribute.FormatFlags.DIMENSION
import com.android.aapt.Resources.Attribute.FormatFlags.ENUM
import com.android.aapt.Resources.Attribute.FormatFlags.FLAGS
import com.android.aapt.Resources.Attribute.FormatFlags.INTEGER
import com.android.aapt.Resources.Attribute.FormatFlags.REFERENCE
import com.android.aapt.Resources.Attribute.FormatFlags.STRING
import com.android.aaptcompiler.AaptResourceType.ATTR
import com.android.aaptcompiler.AaptResourceType.BOOL
import com.android.aaptcompiler.AaptResourceType.DIMEN
import com.android.aaptcompiler.AaptResourceType.ID
import com.android.aaptcompiler.AaptResourceType.UNKNOWN
import com.android.aaptcompiler.AttributeResource
import com.android.aaptcompiler.ConfigDescription
import com.android.aaptcompiler.ResourcePathData
import com.tom.rv2ide.lsp.api.ICompletionProvider
import com.tom.rv2ide.lsp.models.CompletionItem
import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.CompletionResult.Companion.EMPTY
import com.tom.rv2ide.lsp.models.CompletionResult.Companion.MAX_ITEMS
import com.tom.rv2ide.lsp.models.MatchLevel.NO_MATCH
import com.tom.rv2ide.lsp.xml.edits.QualifiedValueEditHandler
import com.tom.rv2ide.lsp.xml.resources.ModuleResourceIndex
import com.tom.rv2ide.lsp.xml.resources.ResourceDefinition
import com.tom.rv2ide.lsp.xml.resources.ResourceDefinitionKind
import com.tom.rv2ide.lsp.xml.resources.ResourceDefinitionExtractor
import com.tom.rv2ide.lsp.xml.resources.ResourceSnapshot
import com.tom.rv2ide.lsp.xml.utils.XmlUtils.NodeType
import com.tom.rv2ide.lsp.xml.utils.XmlUtils.NodeType.ATTRIBUTE_VALUE
import com.tom.rv2ide.lsp.xml.utils.dimensionUnits
import com.tom.rv2ide.xml.res.IResourceTable
import com.tom.rv2ide.xml.res.IResourceTablePackage
import com.tom.rv2ide.xml.resources.ResourceTableRegistry
import com.tom.rv2ide.xml.utils.attrValue_qualifiedRef
import com.tom.rv2ide.xml.utils.attrValue_qualifiedRefWithIncompletePckOrType
import com.tom.rv2ide.xml.utils.attrValue_qualifiedRefWithIncompleteType
import com.tom.rv2ide.xml.utils.attrValue_unqualifiedRef
import org.eclipse.lemminx.dom.DOMDocument

/**
 * Provides completions for attribute value in layout XML files.
 *
 * @author Akash Yadav
 */
open class AttrValueCompletionProvider(provider: ICompletionProvider) :
    IXmlCompletionProvider(provider) {

  override fun canProvideCompletions(pathData: ResourcePathData, type: NodeType): Boolean {
    return super.canProvideCompletions(pathData, type) && type == ATTRIBUTE_VALUE
  }

  override fun doComplete(
      params: CompletionParams,
      pathData: ResourcePathData,
      document: DOMDocument,
      type: NodeType,
      prefix: String,
  ): CompletionResult {
    val attrName =
        attrAtCursor.localName
            ?: run {
              log.warn("Cannot find attribute at index {}", params.position.index)
              return EMPTY
            }

    // TODO Currently we do not support completing values for attributes without a namespace
    //  For example, completions will be provided for: 'android:textColor="@@cursor@@"' but
    //  not for 'textColor="@@cursor"'

    val namespace =
        attrAtCursor.namespaceURI
            ?: run {
              log.warn("Unknown namespace for attribute: {}", attrAtCursor)
              return EMPTY
            }

    val tableResult = completeValue(namespace = namespace, prefix = prefix, attrName = attrName)
    val workspaceQuery =
        parseCreatingIdCompletionQuery(attrAtCursor.value.orEmpty()) ?: return tableResult
    params.cancelChecker.abortIfCancelled()
    val snapshot =
        ModuleResourceIndex.snapshot(params.file, document.textDocument.text) as? ResourceSnapshot.Available
            ?: return tableResult
    params.cancelChecker.abortIfCancelled()
    val creatingIdItems =
        creatingIdCompletionCandidates(workspaceQuery, snapshot.definitions)
            .map { candidate ->
              createAttrValueCompletionItem(
                  type = candidate.type.tagName,
                  name = candidate.name,
                  matchLevel = matchLevel(candidate.name, workspaceQuery.entryPrefix),
                  referenceMarker = candidate.marker,
              )
            }
    return mergeWorkspaceResourceCompletions(tableResult, creatingIdItems)
  }

  fun setNamespaces(namespaces: Set<Pair<String, String>>) {
    this.allNamespaces = namespaces
  }

  fun completeValue(
      namespace: String?,
      prefix: String,
      attrName: String,
      attrValue: String? = null,
  ): CompletionResult {

    if (namespace.isNullOrBlank()) {
      return EMPTY
    }

    val tables = findResourceTables(namespace)
    if (tables.isEmpty()) {
      return EMPTY
    }

    val pck = namespace.substringAfter(NAMESPACE_PREFIX)
    val list = mutableListOf<CompletionItem>()

    val attr =
        findAttr(tables, namespace, pck, attrName)
            ?: run {
              log.warn(
                  "No attribute found with name '{}' in package '{}'",
                  attrName,
                  if (namespace == NAMESPACE_AUTO) "<auto>" else pck,
              )
              return EMPTY
            }

    val value = attrValue ?: this.attrAtCursor.value

    // Theme references can only target ATTR resources. Keep this separate from the generic '@'
    // reference path so '?string/...' and other invalid theme reference types are never suggested.
    if (value.startsWith('?')) {
      parseThemeReferenceQueries(value).forEach { query ->
        addThemeAttributeValues(query, list)
      }
      return CompletionResult(list)
    }

    // If user is directly typing the entry name. For example 'app_name'
    if (!value.startsWith('@')) {
      addValuesForAttr(attr, pck, prefix, list)
      return CompletionResult(list)
    }

    // If user is typign entry with package name and resource type. For example
    // '@com.itsaky.test.app:string/app_name' or '@android:string/ok'
    var matcher = attrValue_qualifiedRef.matcher(value)
    if (matcher.matches()) {
      val valPck = matcher.group(1)
      val typeStr = matcher.group(3)
      val valType =
          com.android.aaptcompiler.AaptResourceType.values().firstOrNull { it.tagName == typeStr }
              ?: return EMPTY
      val newPrefix = matcher.group(4) ?: ""
      addValues(valType, newPrefix, list) { it == valPck }
      return CompletionResult(list)
    }

    // If user is typing qualified reference but with incomplete type
    // For example: '@android:str' or '@com.itsaky.test.app:str'
    matcher = attrValue_qualifiedRefWithIncompleteType.matcher(value)
    if (matcher.matches()) {
      val valPck = matcher.group(1)!!
      val incompleteType = matcher.group(3) ?: ""
      addResourceTypes(valPck, incompleteType, list)
      return CompletionResult(list)
    }

    // If user is typing qualified reference but with incomplete type or package name
    // For example: '@android:str' or '@str'
    matcher = attrValue_qualifiedRefWithIncompletePckOrType.matcher(value)
    if (matcher.matches()) {
      val valPck = matcher.group(1)!!

      if (!valPck.contains('.')) {
        addResourceTypes("", valPck, list)
      }

      addPackages(valPck, list)

      return CompletionResult(list)
    }

    // If user is typing entry name with resource type. For example '@string/app_name'
    matcher = attrValue_unqualifiedRef.matcher(value)
    if (matcher.matches()) {
      val typeStr = matcher.group(1)
      val newPrefix = matcher.group(2) ?: ""
      val valType =
          com.android.aaptcompiler.AaptResourceType.values().firstOrNull { it.tagName == typeStr }
              ?: return EMPTY
      addValues(valType, newPrefix, list)
      return CompletionResult(list)
    }

    return EMPTY
  }

  private fun addThemeAttributeValues(
      query: ThemeReferenceQuery,
      list: MutableList<CompletionItem>,
  ) {
    val entries =
        allNamespaces
            .flatMap { findResourceTables(it.second) }
            .flatMap { table ->
              table.packages.mapNotNull { resourcePackage ->
                if (query.packageName == ResourceTableRegistry.PCK_ANDROID &&
                    resourcePackage.name != ResourceTableRegistry.PCK_ANDROID) {
                  return@mapNotNull null
                }
                if (query.packageName == null &&
                    resourcePackage.name == ResourceTableRegistry.PCK_ANDROID) {
                  return@mapNotNull null
                }
                resourcePackage.name to
                    resourcePackage.findGroup(ATTR)?.findEntries { entryName ->
                      matchLevel(entryName, query.entryPrefix) != NO_MATCH
                    }
              }
            }
            .toHashSet()

    entries.forEach { (packageName, packageEntries) ->
      packageEntries?.forEach { entry ->
        if (list.size >= MAX_ITEMS + 1) {
          return
        }
        list.add(
            createAttrValueCompletionItem(
                pck = packageName,
                type = ATTR.tagName,
                name = entry.name,
                matchLevel = matchLevel(entry.name, query.entryPrefix),
                referenceMarker = '?',
            )
        )
      }
    }
  }

  private fun addPackages(incompletePck: String, list: MutableList<CompletionItem>) {
    val packages =
        findResourceTables(ANDROID_URI).flatMap {
          it.packages.filter { pck -> matchLevel(pck.name, incompletePck) != NO_MATCH }
        }
    packages.forEach {
      val match = matchLevel(it.name, incompletePck)
      val item = createEnumOrFlagCompletionItem(it.name, it.name, match)
      item.editHandler = QualifiedValueEditHandler()
      list.add(item)
    }
  }

  private fun addResourceTypes(
      pck: String,
      incompleteType: String,
      list: MutableList<CompletionItem>,
  ) {
    listResTypes().forEach {
      val match = matchLevel(it, incompleteType)
      if (match == NO_MATCH && incompleteType.isNotBlank()) {
        return@forEach
      }

      val item = createEnumOrFlagCompletionItem(pck, it, match)
      item.overrideTypeText = "Resource type"
      list.add(item)
    }
  }

  private fun listResTypes(): List<String> =
      com.android.aaptcompiler.AaptResourceType.values().map { it.tagName }

  protected open fun resTableForFindAttr() = platformResourceTable()

  private fun findAttr(
      tables: Set<IResourceTable>,
      namespace: String,
      pck: String,
      attr: String,
  ): AttributeResource? {
    if (namespace != NAMESPACE_AUTO && pck == ResourceTableRegistry.PCK_ANDROID) {
      // AndroidX dependencies include attribute declarations with the 'android' package
      // Those must not be included when completing values
      val attrEntry =
          resTableForFindAttr()!!
              .findPackage(ResourceTableRegistry.PCK_ANDROID)
              ?.findGroup(ATTR)
              ?.findEntry(attr)
              ?.findValue(ConfigDescription())
              ?.value
      return if (attrEntry is AttributeResource) attrEntry else null
    }

    return if (namespace == NAMESPACE_AUTO) {
      findAttr(tables.flatMap { it.packages }, attr)
    } else {
      findAttr(tables.mapNotNull { it.findPackage(pck) }, attr)
    }
  }

  private fun findAttr(
      packages: Collection<IResourceTablePackage>,
      attr: String,
  ): AttributeResource? {
    for (pck in packages) {
      val entry =
          pck.findGroup(ATTR)?.findEntry(attr)?.findValue(ConfigDescription())?.value ?: continue
      if (entry is AttributeResource) {
        return entry
      }
    }
    return null
  }

  private fun addValuesForAttr(
      attr: AttributeResource,
      pck: String,
      prefix: String,
      list: MutableList<CompletionItem>,
  ) {
    if (attr.typeMask == FormatFlags.REFERENCE_VALUE) {
      completeReferences(prefix, list)
    } else {
      // Check for specific attribute formats
      if (attr.hasType(STRING)) {
        addValues(
            type = com.android.aaptcompiler.AaptResourceType.STRING,
            prefix = prefix,
            result = list,
        )
      }

      if (attr.hasType(INTEGER)) {
        addValues(
            type = com.android.aaptcompiler.AaptResourceType.INTEGER,
            prefix = prefix,
            result = list,
        )
      }

      if (attr.hasType(COLOR)) {
        addValues(
            type = com.android.aaptcompiler.AaptResourceType.COLOR,
            prefix = prefix,
            result = list,
        )
      }

      if (attr.hasType(BOOLEAN)) {
        addValues(type = BOOL, prefix = prefix, result = list)
      }

      if (attr.hasType(DIMENSION)) {
        if (prefix.isNotBlank() && prefix[0].isDigit()) {
          addConstantDimensionValues(prefix, list)
        } else addValues(type = DIMEN, prefix = prefix, result = list)
      }

      if (attr.hasType(INTEGER)) {
        addValues(
            type = com.android.aaptcompiler.AaptResourceType.INTEGER,
            prefix = prefix,
            result = list,
        )
      }

      if (attr.hasType(ENUM) || attr.hasType(FLAGS)) {
        for (symbol in attr.symbols) {
          val matchLevel = matchLevel(symbol.symbol.name.entry!!, prefix)
          if (matchLevel == NO_MATCH && prefix.isNotEmpty()) {
            continue
          }

          list.add(
              createEnumOrFlagCompletionItem(
                  pck = pck,
                  name = symbol.symbol.name.entry!!,
                  matchLevel,
              )
          )
        }
      }

      if (attr.hasType(REFERENCE)) {
        completeReferences(prefix, list)
      }
    }
  }

  private fun addConstantDimensionValues(prefix: String, list: MutableList<CompletionItem>) {
    var i = 0
    while (i < prefix.length && prefix[i].isDigit()) {
      ++i
    }
    val dimen = prefix.substring(0, i)
    for (unit in dimensionUnits) {
      val value = "${dimen}${unit}"
      val matchLevel = matchLevel(value, prefix)
      if (matchLevel == NO_MATCH) {
        continue
      }
      list.add(createEnumOrFlagCompletionItem(name = value, matchLevel = matchLevel))
    }
  }

  private fun completeReferences(prefix: String, list: MutableList<CompletionItem>) {
    for (value in com.android.aaptcompiler.AaptResourceType.values()) {
      if (value == UNKNOWN) {
        continue
      }

      addValues(value, prefix, list)
    }
  }

  private fun addValues(
      type: com.android.aaptcompiler.AaptResourceType,
      prefix: String,
      result: MutableList<CompletionItem>,
      checkPck: (String) -> Boolean = { true },
  ) {
    if (result.size >= MAX_ITEMS + 1) {
      return
    }

    val entries =
        allNamespaces
            .flatMap { findResourceTables(it.second) }
            .flatMap { table ->
              table.packages.mapNotNull { pck ->
                if (!checkPck(pck.name)) {
                  return@mapNotNull null
                }
                pck.name to
                    pck.findGroup(type)?.findEntries { entryName ->
                      matchLevel(entryName, prefix) != NO_MATCH
                    }
              }
            }
            .toHashSet()

    entries.forEach { pair ->
      pair.second?.forEach { entry ->
        result.add(
            createAttrValueCompletionItem(
                pair.first,
                type.tagName,
                entry.name,
                matchLevel(entry.name, prefix),
            )
        )
      }
    }
  }

  override fun findResourceTables(nsUri: String?): Set<IResourceTable> {
    // When completing values, all namespaces must be included
    val tables = HashSet(findAllModuleResourceTables())

    if (nsUri.isNullOrBlank()) {
      return tables
    }

    tables.addAll(super.findResourceTables(nsUri))
    log.info("Found {} resource tables for namespace: {}", tables.size, nsUri)
    return tables
  }

  private fun AttributeResource.hasType(check: FormatFlags): Boolean {
    return hasType(check.number)
  }

  private fun AttributeResource.hasType(check: Int): Boolean {
    return this.typeMask and check != 0
  }
}

internal data class WorkspaceResourceCompletionQuery(
    val marker: Char,
    val type: com.android.aaptcompiler.AaptResourceType,
    val entryPrefix: String,
)

internal data class WorkspaceResourceCompletionCandidate(
    val marker: Char,
    val type: com.android.aaptcompiler.AaptResourceType,
    val name: String,
)

/** Parses the only query shape allowed to consult the editable-resource creating-ID fallback. */
internal fun parseCreatingIdCompletionQuery(
    value: String,
): WorkspaceResourceCompletionQuery? {
  if (!value.startsWith('@')) return null
  val body = value.drop(1)
  if (body.startsWith('+') || ':' in body) return null
  val separator = body.indexOf('/')
  if (separator <= 0 || body.indexOf('/', separator + 1) >= 0) return null
  val typeName = body.substring(0, separator)
  val entryPrefix = body.substring(separator + 1)
  if (typeName != ID.tagName || !RESOURCE_COMPLETION_ENTRY_PREFIX.matches(entryPrefix)) {
    return null
  }
  return WorkspaceResourceCompletionQuery('@', ID, entryPrefix)
}

/**
 * The published resource table is the source of completion candidates. This narrow fallback covers
 * only `@+id` declarations in editable non-values resource XML, which are not represented by the
 * resource table. This preserves creating-ID completion during unsaved editing without making the
 * editable-resource index a general completion source.
 */
internal fun creatingIdCompletionCandidates(
    query: WorkspaceResourceCompletionQuery,
    definitions: List<ResourceDefinition>,
): List<WorkspaceResourceCompletionCandidate> {
  if (query.marker != '@' || query.type != ID) return emptyList()
  return definitions
      .asSequence()
      .filter { it.kind == ResourceDefinitionKind.CREATING_ID_DECLARATION }
      .filter { ResourceDefinitionExtractor.categoryOf(it.sourceFile) == ResourceDefinitionExtractor.Category.FILE }
      .filter { it.type == ID && it.name.startsWith(query.entryPrefix) }
      .map { WorkspaceResourceCompletionCandidate(query.marker, it.type, it.name) }
      .distinctBy { it.name }
      .sortedBy { it.name }
      .take(MAX_ITEMS + 1)
      .toList()
}

internal fun mergeWorkspaceResourceCompletions(
    tableResult: CompletionResult,
    workspaceItems: List<CompletionItem>,
): CompletionResult {
  val items = LinkedHashMap<String, CompletionItem>()
  tableResult.items.forEach { item -> items.putIfAbsent(item.insertText, item) }
  workspaceItems.forEach { item -> items.putIfAbsent(item.insertText, item) }
  return CompletionResult(items.values).also { result ->
    result.isIncomplete = tableResult.isIncomplete || result.isIncomplete
  }
}

private val RESOURCE_COMPLETION_ENTRY_PREFIX = Regex("[A-Za-z0-9_.-]*")

internal data class ThemeReferenceQuery(
    val packageName: String?,
    val entryPrefix: String,
)

internal fun parseThemeReferenceQueries(value: String): List<ThemeReferenceQuery> {
  if (!value.startsWith('?')) {
    return emptyList()
  }
  val body = value.drop(1)
  val localPrefix = "attr/"
  val frameworkPrefix = "android:attr/"
  return buildList {
    if (localPrefix.startsWith(body)) {
      add(ThemeReferenceQuery(packageName = null, entryPrefix = ""))
    } else if (body.startsWith(localPrefix)) {
      add(ThemeReferenceQuery(packageName = null, entryPrefix = body.removePrefix(localPrefix)))
    }
    if (frameworkPrefix.startsWith(body)) {
      add(ThemeReferenceQuery(packageName = ResourceTableRegistry.PCK_ANDROID, entryPrefix = ""))
    } else if (body.startsWith(frameworkPrefix)) {
      add(
          ThemeReferenceQuery(
              packageName = ResourceTableRegistry.PCK_ANDROID,
              entryPrefix = body.removePrefix(frameworkPrefix),
          )
      )
    }
  }
}

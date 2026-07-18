/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.xml.diagnostics

import org.eclipse.lemminx.dom.DOMElement

/** A document-level diagnostic rule executed once per immutable diagnostic context. */
internal interface XmlDiagnosticRule {
  val id: String

  fun supports(context: XmlDiagnosticContext): Boolean

  fun diagnose(context: XmlDiagnosticContext, collector: XmlDiagnosticCollector)
}

/** An element-level rule invoked during the service's single DOM traversal. */
internal interface XmlElementDiagnosticRule {
  val id: String

  fun supports(context: XmlDiagnosticContext): Boolean

  fun diagnose(
      element: DOMElement,
      context: XmlDiagnosticContext,
      collector: XmlDiagnosticCollector,
  )
}
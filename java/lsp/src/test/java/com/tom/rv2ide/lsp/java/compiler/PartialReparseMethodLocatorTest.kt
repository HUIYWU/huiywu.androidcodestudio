package com.tom.rv2ide.lsp.java.compiler

import androidx.core.util.Pair
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.models.Range
import openjdk.source.util.TreePath
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PartialReparseMethodLocatorTest {

  @Test
  fun findCurrentMethodReturnsNullWhenPositionsAreEmpty() {
    val result = PartialReparseMethodLocator().findCurrentMethod(emptyList(), 10)

    assertNull(result)
  }

  @Test
  fun findCurrentMethodReturnsNullWhenCursorIsBeforeFirstMethod() {
    val methods = listOf(method(10, 20), method(30, 40))

    val result = PartialReparseMethodLocator().findCurrentMethod(methods, 5)

    assertNull(result)
  }

  @Test
  fun findCurrentMethodReturnsNullWhenCursorIsAfterLastMethod() {
    val methods = listOf(method(10, 20), method(30, 40))

    val result = PartialReparseMethodLocator().findCurrentMethod(methods, 45)

    assertNull(result)
  }

  @Test
  fun findCurrentMethodReturnsNullWhenCursorFallsBetweenMethods() {
    val methods = listOf(method(10, 20), method(30, 40))

    val result = PartialReparseMethodLocator().findCurrentMethod(methods, 25)

    assertNull(result)
  }

  @Test
  fun findCurrentMethodReturnsMatchingMethodWhenCursorIsInsideRange() {
    val first = method(10, 20)
    val second = method(30, 40)
    val third = method(50, 60)

    val result = PartialReparseMethodLocator().findCurrentMethod(listOf(first, second, third), 35)

    assertSame(second, result)
  }

  @Test
  fun findCurrentMethodTreatsStartBoundaryAsInsideRange() {
    val target = method(10, 20)

    val result = PartialReparseMethodLocator().findCurrentMethod(listOf(target), 10)

    assertSame(target, result)
  }

  @Test
  fun findCurrentMethodTreatsEndBoundaryAsInsideRange() {
    val target = method(10, 20)

    val result = PartialReparseMethodLocator().findCurrentMethod(listOf(target), 20)

    assertSame(target, result)
  }

  private fun method(startIndex: Int, endIndex: Int): Pair<Range, TreePath> {
    return Pair(Range(Position(0, 0, startIndex), Position(0, 0, endIndex)), null)
  }
}
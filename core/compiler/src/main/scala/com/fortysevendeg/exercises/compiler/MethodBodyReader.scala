/*
 * scala-exercises-exercise-compiler
 * Copyright (C) 2015-2016 47 Degrees, LLC. <http://www.47deg.com>
 */

package com.fortysevendeg.exercises
package compiler

import scala.annotation.tailrec

import scala.reflect.internal.Chars.isWhitespace
import scala.tools.nsc.Global

object MethodBodyReader {

  /** Attempts to read (and clean) a method body.
    */
  def read[G <: Global](g: G)(tree: g.Tree): String = {
    val (bodyStart, bodyEnd) = bodyRange(g)(tree)

    val content = tree.pos.source.content
    val lineRanges = normalizedLineRanges(content, bodyStart, bodyEnd)

    lineRanges
      .map { lineRange ⇒ content.slice(lineRange._1, lineRange._2).mkString }
      .mkString("\n")
  }

  /** Finds the text range for the body of the method.
    * This should:
    * - ignore the wrapping block brackets
    * - include any leading whitespace before the first expression
    * in multi line statements
    */
  def bodyRange[G <: Global](g: G)(tree: g.Tree): (Int, Int) = {
    import g._
    tree match {
      case Block(stats, expr) ⇒
        val firstTree = if (stats.nonEmpty) stats.head else expr
        val lastTree = expr
        val start = firstTree.pos.start
        val end = lastTree.pos.end
        val start0 = backstepWhitespace(
          tree.pos.source.content, tree.pos.start, start
        )
        (start0, end)
      case _ ⇒
        (tree.pos.start, tree.pos.end)
    }
  }

  @tailrec private def backstepWhitespace(str: Array[Char], start: Int, end: Int): Int = {
    if (end > start && isWhitespace(str(end - 1)))
      backstepWhitespace(str, start, end - 1)
    else end
  }

  /** This attempts to find all the individual lines in a method body
    * while also counting the amount of common prefix whitespace on each line.
    */
  private def normalizedLineRanges(str: Array[Char], start: Int, end: Int): List[(Int, Int)] = {

    @tailrec def skipToEol(offset: Int): Int =
      if (offset < end && str(offset) != '\n') skipToEol(offset + 1)
      else offset

    @tailrec def skipWhitespace(offset: Int): Int =
      if (offset < end && isWhitespace(str(offset))) skipWhitespace(offset + 1)
      else offset

    type Acc = List[(Int, Int)]
    @tailrec def loop(i: Int, minSpace: Int, acc: Acc): (Int, Acc) = {
      if (i >= end) minSpace → acc
      else {
        val lineStart = skipWhitespace(i)
        val lineEnd = skipToEol(lineStart)

        if (lineStart == lineEnd) loop(
          lineEnd + 1,
          minSpace,
          (i, i) :: acc
        )
        else loop(
          lineEnd + 1,
          math.min(lineStart - i, minSpace),
          (i, lineEnd) :: acc
        )
      }
    }

    val (minSpace, offsets) = loop(start, Int.MaxValue, Nil)
    offsets
      .map { kv ⇒ (kv._1 + minSpace) → kv._2 }
      .reverse
  }

}

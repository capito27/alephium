// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm.{Instr, StatelessContext}

object ArrayTransformer {
  @inline def arrayVarName(baseName: String, idx: Int): String = s"_$baseName-$idx"

  def flattenArrayExprs[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      exprs: Seq[Ast.Expr[Ctx]]
  ): Seq[Instr[Ctx]] = {
    exprs.flatMap(expr =>
      expr.getType(state) match {
        case Seq(_: Type.FixedSizeArray) =>
          val (arrayRef, codes) = state.getOrCreateArrayRef(expr, isMutable = false)
          val loadCodes         = arrayRef.vars.map(state.genLoadCode)
          codes ++ loadCodes
        case _ => expr.genCode(state)
      }
    )
  }

  def flattenArgVars[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      args: Seq[Ast.Argument]
  ): Unit = {
    args.foreach { case Ast.Argument(ident, tpe, isMutable) =>
      tpe match {
        case tpe: Type.FixedSizeArray =>
          state.addVariable(ident, tpe, isMutable)
          val arrayRef =
            ArrayRef(tpe, flattenArrayVars(state, tpe, ident.name, isMutable))
          state.addArrayRef(ident, arrayRef)
        case _ =>
          state.addVariable(ident, tpe, isMutable)
      }
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private def flattenArrayVars[Ctx <: StatelessContext](
      state: Compiler.State[Ctx],
      tpe: Type.FixedSizeArray,
      baseName: String,
      isMutable: Boolean
  ): Seq[Ast.Ident] = {
    tpe.baseType match {
      case baseType: Type.FixedSizeArray =>
        (0 until tpe.size).flatMap { idx =>
          val newBaseName = arrayVarName(baseName, idx)
          flattenArrayVars(state, baseType, newBaseName, isMutable)
        }
      case baseType =>
        (0 until tpe.size).map { idx =>
          val ident = Ast.Ident(arrayVarName(baseName, idx))
          state.addVariable(ident, baseType, isMutable)
          ident
        }
    }
  }

  def flattenTypeLength(types: Seq[Type]): Int = {
    types.foldLeft(0) { case (acc, tpe) =>
      tpe match {
        case t: Type.FixedSizeArray => acc + t.flattenSize()
        case _                      => acc + 1
      }
    }
  }

  @inline def checkArrayIndex(index: Int, arraySize: Int): Unit = {
    if (index < 0 || index >= arraySize) {
      throw Compiler.Error(s"Invalid index: $index, array size: $arraySize")
    }
  }

  final case class ArrayRef(tpe: Type.FixedSizeArray, vars: Seq[Ast.Ident]) {
    def subArray(index: Int): ArrayRef = {
      tpe.baseType match {
        case baseType: Type.FixedSizeArray =>
          checkArrayIndex(index, tpe.size)
          val length = baseType.flattenSize()
          val offset = index * length
          ArrayRef(baseType, vars.slice(offset, offset + length))
        case _ =>
          throw Compiler.Error(s"Expect multi-dimension array type, have $tpe")
      }
    }

    @scala.annotation.tailrec
    def subArray(indexes: Seq[Int]): ArrayRef = {
      if (indexes.isEmpty) {
        this
      } else {
        subArray(indexes(0)).subArray(indexes.drop(1))
      }
    }

    @scala.annotation.tailrec
    def getVariable(indexes: Seq[Int]): Ast.Ident = {
      assume(indexes.nonEmpty)
      if (indexes.size == 1) {
        getVariable(indexes(0))
      } else {
        subArray(indexes(0)).getVariable(indexes.drop(1))
      }
    }

    def getVariable(index: Int): Ast.Ident = {
      checkArrayIndex(index, tpe.size)
      vars(index)
    }
  }

  object ArrayRef {
    def from[Ctx <: StatelessContext](
        state: Compiler.State[Ctx],
        tpe: Type.FixedSizeArray,
        baseName: String,
        isMutable: Boolean
    ): ArrayRef = {
      ArrayRef(tpe, flattenArrayVars(state, tpe, baseName, isMutable))
    }
  }
}

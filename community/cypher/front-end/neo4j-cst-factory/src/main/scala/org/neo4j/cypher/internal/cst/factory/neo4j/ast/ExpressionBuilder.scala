/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.cypher.internal.cst.factory.neo4j.ast

import org.antlr.v4.runtime.misc.Interval
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNode
import org.neo4j.cypher.internal.ast.CollectExpression
import org.neo4j.cypher.internal.ast.IsNormalized
import org.neo4j.cypher.internal.ast.IsNotNormalized
import org.neo4j.cypher.internal.ast.IsNotTyped
import org.neo4j.cypher.internal.ast.IsTyped
import org.neo4j.cypher.internal.cst.factory.neo4j.ast.Util.astBinaryFold
import org.neo4j.cypher.internal.cst.factory.neo4j.ast.Util.astChild
import org.neo4j.cypher.internal.cst.factory.neo4j.ast.Util.astChildListSet
import org.neo4j.cypher.internal.cst.factory.neo4j.ast.Util.astCtxReduce
import org.neo4j.cypher.internal.cst.factory.neo4j.ast.Util.astOpt
import org.neo4j.cypher.internal.cst.factory.neo4j.ast.Util.astPairs
import org.neo4j.cypher.internal.cst.factory.neo4j.ast.Util.astSeq
import org.neo4j.cypher.internal.cst.factory.neo4j.ast.Util.child
import org.neo4j.cypher.internal.cst.factory.neo4j.ast.Util.ctxChild
import org.neo4j.cypher.internal.cst.factory.neo4j.ast.Util.lastChild
import org.neo4j.cypher.internal.cst.factory.neo4j.ast.Util.nodeChild
import org.neo4j.cypher.internal.cst.factory.neo4j.ast.Util.nodeChildType
import org.neo4j.cypher.internal.cst.factory.neo4j.ast.Util.pos
import org.neo4j.cypher.internal.expressions.Add
import org.neo4j.cypher.internal.expressions.AllIterablePredicate
import org.neo4j.cypher.internal.expressions.And
import org.neo4j.cypher.internal.expressions.Ands
import org.neo4j.cypher.internal.expressions.AnyIterablePredicate
import org.neo4j.cypher.internal.expressions.CaseExpression
import org.neo4j.cypher.internal.expressions.ContainerIndex
import org.neo4j.cypher.internal.expressions.Contains
import org.neo4j.cypher.internal.expressions.Divide
import org.neo4j.cypher.internal.expressions.EndsWith
import org.neo4j.cypher.internal.expressions.Equals
import org.neo4j.cypher.internal.expressions.ExplicitParameter
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.FunctionInvocation
import org.neo4j.cypher.internal.expressions.FunctionName
import org.neo4j.cypher.internal.expressions.GreaterThan
import org.neo4j.cypher.internal.expressions.GreaterThanOrEqual
import org.neo4j.cypher.internal.expressions.In
import org.neo4j.cypher.internal.expressions.InvalidNotEquals
import org.neo4j.cypher.internal.expressions.IsNotNull
import org.neo4j.cypher.internal.expressions.IsNull
import org.neo4j.cypher.internal.expressions.LessThan
import org.neo4j.cypher.internal.expressions.LessThanOrEqual
import org.neo4j.cypher.internal.expressions.ListComprehension
import org.neo4j.cypher.internal.expressions.ListSlice
import org.neo4j.cypher.internal.expressions.MapExpression
import org.neo4j.cypher.internal.expressions.Modulo
import org.neo4j.cypher.internal.expressions.Multiply
import org.neo4j.cypher.internal.expressions.NFCNormalForm
import org.neo4j.cypher.internal.expressions.NFDNormalForm
import org.neo4j.cypher.internal.expressions.NFKCNormalForm
import org.neo4j.cypher.internal.expressions.NFKDNormalForm
import org.neo4j.cypher.internal.expressions.Namespace
import org.neo4j.cypher.internal.expressions.NoneIterablePredicate
import org.neo4j.cypher.internal.expressions.NormalForm
import org.neo4j.cypher.internal.expressions.Not
import org.neo4j.cypher.internal.expressions.NotEquals
import org.neo4j.cypher.internal.expressions.Or
import org.neo4j.cypher.internal.expressions.Ors
import org.neo4j.cypher.internal.expressions.Pow
import org.neo4j.cypher.internal.expressions.Property
import org.neo4j.cypher.internal.expressions.PropertyKeyName
import org.neo4j.cypher.internal.expressions.RegexMatch
import org.neo4j.cypher.internal.expressions.ShortestPathExpression
import org.neo4j.cypher.internal.expressions.ShortestPathsPatternPart
import org.neo4j.cypher.internal.expressions.SingleIterablePredicate
import org.neo4j.cypher.internal.expressions.StartsWith
import org.neo4j.cypher.internal.expressions.Subtract
import org.neo4j.cypher.internal.expressions.UnaryAdd
import org.neo4j.cypher.internal.expressions.UnarySubtract
import org.neo4j.cypher.internal.expressions.UnsignedDecimalIntegerLiteral
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.expressions.Xor
import org.neo4j.cypher.internal.label_expressions.LabelExpressionPredicate
import org.neo4j.cypher.internal.macros.AssertMacros
import org.neo4j.cypher.internal.parser.AstRuleCtx
import org.neo4j.cypher.internal.parser.CypherParser
import org.neo4j.cypher.internal.parser.CypherParserListener
import org.neo4j.cypher.internal.util.InputPosition
import org.neo4j.cypher.internal.util.symbols.AnyType
import org.neo4j.cypher.internal.util.symbols.BooleanType
import org.neo4j.cypher.internal.util.symbols.CTAny
import org.neo4j.cypher.internal.util.symbols.CTMap
import org.neo4j.cypher.internal.util.symbols.CTString
import org.neo4j.cypher.internal.util.symbols.ClosedDynamicUnionType
import org.neo4j.cypher.internal.util.symbols.CypherType
import org.neo4j.cypher.internal.util.symbols.DateType
import org.neo4j.cypher.internal.util.symbols.DurationType
import org.neo4j.cypher.internal.util.symbols.FloatType
import org.neo4j.cypher.internal.util.symbols.IntegerType
import org.neo4j.cypher.internal.util.symbols.ListType
import org.neo4j.cypher.internal.util.symbols.LocalDateTimeType
import org.neo4j.cypher.internal.util.symbols.LocalTimeType
import org.neo4j.cypher.internal.util.symbols.MapType
import org.neo4j.cypher.internal.util.symbols.NodeType
import org.neo4j.cypher.internal.util.symbols.NothingType
import org.neo4j.cypher.internal.util.symbols.NullType
import org.neo4j.cypher.internal.util.symbols.PathType
import org.neo4j.cypher.internal.util.symbols.PointType
import org.neo4j.cypher.internal.util.symbols.PropertyValueType
import org.neo4j.cypher.internal.util.symbols.RelationshipType
import org.neo4j.cypher.internal.util.symbols.StringType
import org.neo4j.cypher.internal.util.symbols.ZonedDateTimeType
import org.neo4j.cypher.internal.util.symbols.ZonedTimeType

import scala.collection.immutable.ArraySeq
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.IterableHasAsScala

trait ExpressionBuilder extends CypherParserListener {

  final override def exitProcedureName(
    ctx: CypherParser.ProcedureNameContext
  ): Unit = {}

  final override def exitProcedureArgument(
    ctx: CypherParser.ProcedureArgumentContext
  ): Unit = {}

  final override def exitProcedureResultItem(
    ctx: CypherParser.ProcedureResultItemContext
  ): Unit = {}

  final override def exitSubqueryInTransactionsParameters(
    ctx: CypherParser.SubqueryInTransactionsParametersContext
  ): Unit = {}

  final override def exitSubqueryInTransactionsBatchParameters(
    ctx: CypherParser.SubqueryInTransactionsBatchParametersContext
  ): Unit = {}

  final override def exitSubqueryInTransactionsErrorParameters(
    ctx: CypherParser.SubqueryInTransactionsErrorParametersContext
  ): Unit = {}

  final override def exitSubqueryInTransactionsReportParameters(
    ctx: CypherParser.SubqueryInTransactionsReportParametersContext
  ): Unit = {}

  final override def exitPatternList(
    ctx: CypherParser.PatternListContext
  ): Unit = {}

  final override def exitInsertPatternList(
    ctx: CypherParser.InsertPatternListContext
  ): Unit = {}

  final override def exitPattern(
    ctx: CypherParser.PatternContext
  ): Unit = {}

  final override def exitInsertPattern(
    ctx: CypherParser.InsertPatternContext
  ): Unit = {}

  final override def exitQuantifier(
    ctx: CypherParser.QuantifierContext
  ): Unit = {}

  final override def exitAnonymousPattern(
    ctx: CypherParser.AnonymousPatternContext
  ): Unit = {}

  final override def exitShortestPathPattern(
    ctx: CypherParser.ShortestPathPatternContext
  ): Unit = {}

  final override def exitMaybeQuantifiedRelationshipPattern(
    ctx: CypherParser.MaybeQuantifiedRelationshipPatternContext
  ): Unit = {}

  final override def exitPatternElement(
    ctx: CypherParser.PatternElementContext
  ): Unit = {}

  final override def exitPathPatternAtoms(
    ctx: CypherParser.PathPatternAtomsContext
  ): Unit = {}

  final override def exitInsertPathPatternAtoms(
    ctx: CypherParser.InsertPathPatternAtomsContext
  ): Unit = {}

  final override def exitSelector(
    ctx: CypherParser.SelectorContext
  ): Unit = {}

  final override def exitPathPatternNonEmpty(
    ctx: CypherParser.PathPatternNonEmptyContext
  ): Unit = {}

  final override def exitInsertNodePattern(
    ctx: CypherParser.InsertNodePatternContext
  ): Unit = {}

  final override def exitParenthesizedPath(
    ctx: CypherParser.ParenthesizedPathContext
  ): Unit = {}

  final override def exitProperties(
    ctx: CypherParser.PropertiesContext
  ): Unit = {
    ctx.ast = ctxChild(ctx, 0).ast
  }

  final override def exitInsertRelationshipPattern(
    ctx: CypherParser.InsertRelationshipPatternContext
  ): Unit = {}

  final override def exitLeftArrow(
    ctx: CypherParser.LeftArrowContext
  ): Unit = {}

  final override def exitArrowLine(
    ctx: CypherParser.ArrowLineContext
  ): Unit = {}

  final override def exitRightArrow(
    ctx: CypherParser.RightArrowContext
  ): Unit = {}

  final override def exitPathLength(
    ctx: CypherParser.PathLengthContext
  ): Unit = {
    ctx.ast = if (ctx.DOTDOT() != null) {
      val from = if (ctx.from != null) Some(UnsignedDecimalIntegerLiteral(ctx.from.getText)(pos(ctx.from))) else None
      val to = if (ctx.to != null) Some(UnsignedDecimalIntegerLiteral(ctx.to.getText)(pos(ctx.to))) else None
      org.neo4j.cypher.internal.expressions.Range(from, to)(from.map(_.position).getOrElse(pos(ctx)))
    } else if (ctx.single != null) {
      val single = Some(UnsignedDecimalIntegerLiteral(ctx.single.getText)(pos(ctx.single)))
      org.neo4j.cypher.internal.expressions.Range(single, single)(pos(ctx))
    } else org.neo4j.cypher.internal.expressions.Range(None, None)(pos(ctx))
  }

  final override def exitExpression(ctx: CypherParser.ExpressionContext): Unit = {
    AssertMacros.checkOnlyWhenAssertionsAreEnabled(ctx.getChildCount % 2 == 1)
    ctx.ast = ctx.children.size match {
      case 1 => ctxChild(ctx, 0).ast
      case 3 => Or(astChild(ctx, 0), astChild(ctx, 2))(pos(nodeChild(ctx, 1)))
      case _ => Ors(astChildListSet(ctx))(pos(ctx))
    }
  }

  final override def exitExpression11(ctx: CypherParser.Expression11Context): Unit = {
    AssertMacros.checkOnlyWhenAssertionsAreEnabled(ctx.getChildCount % 2 == 1)
    ctx.ast = astBinaryFold[Expression](ctx, (lhs, token, rhs) => Xor(lhs, rhs)(pos(token)))
  }

  final override def exitExpression10(ctx: CypherParser.Expression10Context): Unit = {
    AssertMacros.checkOnlyWhenAssertionsAreEnabled(ctx.getChildCount % 2 == 1)
    ctx.ast = astBinaryFold[Expression](ctx, (lhs, token, rhs) => And(lhs, rhs)(pos(token)))
  }

  final override def exitExpression9(ctx: CypherParser.Expression9Context): Unit = {
    AssertMacros.checkOnlyWhenAssertionsAreEnabled(ctx.expression8() == lastChild(ctx))
    ctx.ast = ctx.children.size match {
      case 1 => ctxChild(ctx, 0).ast
      case 2 => Not(astChild(ctx, 1))(pos(ctx))
      case _ => ctx.NOT().asScala.foldRight(lastChild[AstRuleCtx](ctx).ast[Expression]()) { case (not, acc) =>
          Not(acc)(pos(not.getSymbol))
        }
    }
  }

  final override def exitExpression8(ctx: CypherParser.Expression8Context): Unit = {
    AssertMacros.checkOnlyWhenAssertionsAreEnabled(ctx.getChildCount % 2 == 1)
    ctx.ast = ctx.children.size match {
      case 1 => ctxChild(ctx, 0).ast
      case 3 => binaryPredicate(ctxChild(ctx, 0).ast(), child(ctx, 1), child(ctx, 2))
      case _ =>
        Ands(ctx.children.asScala.toSeq.sliding(3, 2).map {
          case Seq(lhs: AstRuleCtx, token: TerminalNode, rhs: AstRuleCtx) => binaryPredicate(lhs.ast(), token, rhs)
          case _ => throw new IllegalStateException(s"Unexpected parse results $ctx")
        })(pos(nodeChild(ctx, 1)))
    }
  }

  private def binaryPredicate(lhs: Expression, token: TerminalNode, rhs: AstRuleCtx): Expression = {
    token.getSymbol.getType match {
      case CypherParser.EQ          => Equals(lhs, rhs.ast())(pos(token.getSymbol))
      case CypherParser.INVALID_NEQ => InvalidNotEquals(lhs, rhs.ast())(pos(token.getSymbol))
      case CypherParser.NEQ         => NotEquals(lhs, rhs.ast())(pos(token.getSymbol))
      case CypherParser.LE          => LessThanOrEqual(lhs, rhs.ast())(pos(token.getSymbol))
      case CypherParser.GE          => GreaterThanOrEqual(lhs, rhs.ast())(pos(token.getSymbol))
      case CypherParser.LT          => LessThan(lhs, rhs.ast())(pos(token.getSymbol))
      case CypherParser.GT          => GreaterThan(lhs, rhs.ast())(pos(token.getSymbol))
    }
  }

  final override def exitExpression7(ctx: CypherParser.Expression7Context): Unit = {
    ctx.ast = ctx.children.size match {
      case 1 => ctxChild(ctx, 0).ast
      case _ =>
        val lhs = ctxChild(ctx, 0).ast[Expression]()
        ctxChild(ctx, 1) match {
          case strCtx: CypherParser.StringAndListComparisonContext =>
            stringAndListComparisonExpression(lhs, strCtx)
          case nullCtx: CypherParser.NullComparisonContext =>
            nullComparisonExpression(lhs, nullCtx)
          case typeCtx: CypherParser.TypeComparisonContext =>
            typeComparisonExpression(lhs, typeCtx)
          case nfCtx: CypherParser.NormalFormComparisonContext =>
            normalFormComparisonExpression(lhs, nfCtx.normalForm(), nfCtx.NOT() != null, pos(nfCtx))
          case _ => throw new IllegalStateException(s"Unexpected parse result $ctx")
        }
    }
  }

  private def stringAndListComparisonExpression(lhs: Expression, ctx: AstRuleCtx): Expression = {
    AssertMacros.checkOnlyWhenAssertionsAreEnabled(
      ctx.isInstanceOf[CypherParser.StringAndListComparisonContext] ||
        ctx.isInstanceOf[CypherParser.WhenStringOrListContext]
    )
    val token = child[TerminalNode](ctx, 0).getSymbol
    val rhs = lastChild[AstRuleCtx](ctx).ast[Expression]()
    token.getType match {
      case CypherParser.REGEQ    => RegexMatch(lhs, rhs)(pos(token))
      case CypherParser.STARTS   => StartsWith(lhs, rhs)(pos(token))
      case CypherParser.ENDS     => EndsWith(lhs, rhs)(pos(token))
      case CypherParser.CONTAINS => Contains(lhs, rhs)(pos(token))
      case CypherParser.IN       => In(lhs, rhs)(pos(token))
    }
  }

  private def nullComparisonExpression(lhs: Expression, ctx: AstRuleCtx): Expression = {
    AssertMacros.checkOnlyWhenAssertionsAreEnabled(
      ctx.isInstanceOf[CypherParser.NullComparisonContext] ||
        ctx.isInstanceOf[CypherParser.WhenNullContext]
    )
    if (nodeChildType(ctx, 1) != CypherParser.NOT) IsNull(lhs)(pos(ctx))
    else IsNotNull(lhs)(pos(ctx))
  }

  private def typeComparisonExpression(lhs: Expression, ctx: AstRuleCtx): Expression = {
    AssertMacros.checkOnlyWhenAssertionsAreEnabled(
      ctx.isInstanceOf[CypherParser.TypeComparisonContext] ||
        ctx.isInstanceOf[CypherParser.WhenTypeContext]
    )
    val cypherType = lastChild[AstRuleCtx](ctx).ast[CypherType]()
    val not = child[ParseTree](ctx, 1) match {
      case n: TerminalNode => n.getSymbol.getType == CypherParser.NOT
      case _               => false
    }
    if (not) IsNotTyped(lhs, cypherType)(pos(ctx))
    else IsTyped(lhs, cypherType)(pos(ctx))
  }

  private def normalFormComparisonExpression(
    lhs: Expression,
    nfCtx: CypherParser.NormalFormContext,
    not: Boolean,
    p: InputPosition
  ): Expression = {
    val nf = astOpt[NormalForm](nfCtx, NFCNormalForm)
    if (not) IsNotNormalized(lhs, nf)(p)
    else IsNormalized(lhs, nf)(p)
  }

  final override def exitComparisonExpression6(ctx: CypherParser.ComparisonExpression6Context): Unit = {}

  final override def exitNormalForm(ctx: CypherParser.NormalFormContext): Unit = {
    ctx.ast = child[TerminalNode](ctx, 0).getSymbol.getType match {
      case CypherParser.NFC  => NFCNormalForm
      case CypherParser.NFD  => NFDNormalForm
      case CypherParser.NFKC => NFKCNormalForm
      case CypherParser.NFKD => NFKDNormalForm
    }

  }

  final override def exitExpression6(ctx: CypherParser.Expression6Context): Unit = {
    AssertMacros.checkOnlyWhenAssertionsAreEnabled(ctx.getChildCount % 2 == 1)
    ctx.ast = astBinaryFold(ctx, binaryAdditive)
  }

  private def binaryAdditive(lhs: Expression, token: TerminalNode, rhs: Expression): Expression = {
    token.getSymbol.getType match {
      case CypherParser.PLUS  => Add(lhs, rhs)(pos(token.getSymbol))
      case CypherParser.MINUS => Subtract(lhs, rhs)(pos(token.getSymbol))
    }
  }

  final override def exitExpression5(ctx: CypherParser.Expression5Context): Unit = {
    ctx.ast = astBinaryFold(ctx, binaryMultiplicative)
  }

  private def binaryMultiplicative(lhs: Expression, token: TerminalNode, rhs: Expression): Expression = {
    token.getSymbol.getType match {
      case CypherParser.TIMES   => Multiply(lhs, rhs)(pos(token.getSymbol))
      case CypherParser.DIVIDE  => Divide(lhs, rhs)(pos(token.getSymbol))
      case CypherParser.PERCENT => Modulo(lhs, rhs)(pos(token.getSymbol))
    }
  }

  final override def exitExpression4(ctx: CypherParser.Expression4Context): Unit = {
    AssertMacros.checkOnlyWhenAssertionsAreEnabled(ctx.getChildCount % 2 == 1)
    ctx.ast = astBinaryFold[Expression](ctx, (lhs, token, rhs) => Pow(lhs, rhs)(pos(token.getSymbol)))
  }

  final override def exitExpression3(ctx: CypherParser.Expression3Context): Unit = {
    ctx.ast = ctx.children.size match {
      case 1 => ctxChild(ctx, 0).ast()
      case _ =>
        if (ctx.PLUS() != null) UnaryAdd(lastChild[AstRuleCtx](ctx).ast())(pos(ctx))
        else UnarySubtract(lastChild[AstRuleCtx](ctx).ast())(pos(ctx))
    }
  }

  final override def exitExpression2(
    ctx: CypherParser.Expression2Context
  ): Unit = {
    ctx.ast = ctx.children.size() match {
      case 1 => ctxChild(ctx, 0).ast
      case _ => astCtxReduce(ctx, postFix)
    }
  }

  private def postFix(lhs: Expression, rhs: CypherParser.PostFixContext): Expression = {
    val p = lhs.position
    rhs match {
      case propCtx: CypherParser.PropertyPostfixContext => Property(lhs, ctxChild(propCtx, 1).ast())(p)
      case indexCtx: CypherParser.IndexPostfixContext   => ContainerIndex(lhs, ctxChild(indexCtx, 1).ast())(p)
      case labelCtx: CypherParser.LabelPostfixContext =>
        LabelExpressionPredicate(lhs, ctxChild(labelCtx, 0).ast())(p)
      case rangeCtx: CypherParser.RangePostfixContext =>
        ListSlice(lhs, astOpt(rangeCtx.fromExp), astOpt(rangeCtx.toExp))(p)
      case _ => throw new IllegalStateException(s"Unexpected context $rhs")
    }
  }

  final override def exitPostFix(ctx: CypherParser.PostFixContext): Unit = {}

  final override def exitProperty(ctx: CypherParser.PropertyContext): Unit = {}

  final override def exitPropertyExpression(ctx: CypherParser.PropertyExpressionContext): Unit = {}

  final override def exitExpression1(ctx: CypherParser.Expression1Context): Unit = {
    ctx.ast = ctx.children.size match {
      case 1 => ctxChild(ctx, 0).ast()
      case _ => null
    }
  }

  final override def exitCaseExpression(
    ctx: CypherParser.CaseExpressionContext
  ): Unit = {
    ctx.ast = CaseExpression(
      expression = None,
      alternatives = astSeq(ctx.caseAlternative()),
      default = astOpt(ctx.expression())
    )(pos(ctx))
  }

  final override def exitCaseAlternative(ctx: CypherParser.CaseAlternativeContext): Unit = {
    ctx.ast = (ctxChild(ctx, 1).ast, ctxChild(ctx, 3).ast)
  }

  final override def exitExtendedCaseExpression(ctx: CypherParser.ExtendedCaseExpressionContext): Unit = {
    val caseExp = astChild[Expression](ctx, 1)
    ctx.ast = CaseExpression(
      expression = Some(caseExp),
      alternatives = extendedCaseAlts(caseExp, ctx.extendedCaseAlternative()),
      default = astOpt(ctx.elseExp)
    )(pos(ctx))
  }

  private def extendedCaseAlts(
    lhs: Expression,
    ctxs: java.util.List[CypherParser.ExtendedCaseAlternativeContext]
  ): ArraySeq[(Expression, Expression)] = {
    val size = ctxs.size()
    val resultBuffer = new ArrayBuffer[(Expression, Expression)](size)
    var i = 0
    while (i < size) {
      extendedCaseAlt(resultBuffer, lhs, ctxs.get(i))
      i += 1
    }
    ArraySeq.unsafeWrapArray(resultBuffer.toArray)
  }

  private def extendedCaseAlt(
    buffer: ArrayBuffer[(Expression, Expression)],
    lhs: Expression,
    ctx: CypherParser.ExtendedCaseAlternativeContext
  ): Unit = {
    val size = ctx.children.size()
    var i = 1
    val thenExp = lastChild[AstRuleCtx](ctx).ast[Expression]()
    while (i < size) {
      ctx.children.get(i) match {
        case whenCtx: CypherParser.ExtendedWhenContext =>
          val newWhen = whenCtx match {
            case _: CypherParser.WhenEqualsContext =>
              Equals(lhs, astChild(whenCtx, 0))(pos(nodeChild(ctx, i - 1)))
            case _: CypherParser.WhenComparatorContext =>
              binaryPredicate(lhs, nodeChild(whenCtx, 0), ctxChild(whenCtx, 1))
            case _: CypherParser.WhenStringOrListContext =>
              stringAndListComparisonExpression(lhs, whenCtx)
            case _: CypherParser.WhenNullContext =>
              nullComparisonExpression(lhs, whenCtx)
            case _: CypherParser.WhenTypeContext =>
              typeComparisonExpression(lhs, whenCtx)
            case formCtx: CypherParser.WhenFormContext =>
              normalFormComparisonExpression(lhs, formCtx.normalForm(), formCtx.NOT() != null, pos(formCtx))
            case _ => throw new IllegalStateException(s"Unexepected context $whenCtx")
          }
          buffer.addOne(newWhen -> thenExp)
        case _ =>
      }
      i += 1
    }
  }

  final override def exitExtendedCaseAlternative(ctx: CypherParser.ExtendedCaseAlternativeContext): Unit = {}

  final override def exitExtendedWhen(ctx: CypherParser.ExtendedWhenContext): Unit = {}

  final override def exitListComprehension(ctx: CypherParser.ListComprehensionContext): Unit = {
    ctx.ast = ListComprehension(
      variable = ctxChild(ctx, 1).ast(),
      expression = ctxChild(ctx, 3).ast(),
      innerPredicate = if (ctx.whereExp != null) Some(ctx.whereExp.ast()) else None,
      extractExpression = if (ctx.barExp != null) Some(ctx.barExp.ast()) else None
    )(pos(ctx))
  }

  final override def exitPatternComprehension(
    ctx: CypherParser.PatternComprehensionContext
  ): Unit = {}

  final override def exitReduceExpression(
    ctx: CypherParser.ReduceExpressionContext
  ): Unit = {}

  final override def exitListItemsPredicate(ctx: CypherParser.ListItemsPredicateContext): Unit = {
    val p = pos(ctx)
    val variable = ctx.variable().ast[Variable]()
    val inExp = ctx.inExp.ast[Expression]()
    val where = if (ctx.whereExp != null) Some(ctx.whereExp.ast[Expression]()) else None
    ctx.ast = nodeChild(ctx, 0).getSymbol.getType match {
      case CypherParser.ALL    => AllIterablePredicate(variable, inExp, where)(p)
      case CypherParser.ANY    => AnyIterablePredicate(variable, inExp, where)(p)
      case CypherParser.NONE   => NoneIterablePredicate(variable, inExp, where)(p)
      case CypherParser.SINGLE => SingleIterablePredicate(variable, inExp, where)(p)
    }
  }

  final override def exitPatternExpression(
    ctx: CypherParser.PatternExpressionContext
  ): Unit = {}

  final override def exitShortestPathExpression(
    ctx: CypherParser.ShortestPathExpressionContext
  ): Unit = {
    ctx.ast = ShortestPathExpression(ShortestPathsPatternPart(null, true)(pos(ctx)))
  }

  final override def exitParenthesizedExpression(
    ctx: CypherParser.ParenthesizedExpressionContext
  ): Unit = { ctx.ast = ctxChild(ctx, 1).ast }

  final override def exitMapProjection(
    ctx: CypherParser.MapProjectionContext
  ): Unit = {}

  final override def exitMapProjectionItem(
    ctx: CypherParser.MapProjectionItemContext
  ): Unit = {}

  final override def exitExistsExpression(
    ctx: CypherParser.ExistsExpressionContext
  ): Unit = {}

  final override def exitCountExpression(
    ctx: CypherParser.CountExpressionContext
  ): Unit = {}

  final override def exitCollectExpression(
    ctx: CypherParser.CollectExpressionContext
  ): Unit = {
    ctx.ast = CollectExpression(null)(pos(ctx), null, null)
  }

  final override def exitPropertyKeyName(ctx: CypherParser.PropertyKeyNameContext): Unit = {
    ctx.ast = PropertyKeyName(ctxChild(ctx, 0).ast())(pos(ctx))
  }

  final override def exitParameter(ctx: CypherParser.ParameterContext): Unit = {
    val parameterType = ctx.paramType match {
      case "STRING" => CTString
      case "MAP"    => CTMap
      case _        => CTAny
    }
    val name: String = child[ParseTree](ctx, 1) match {
      case strCtx: CypherParser.SymbolicNameStringContext => strCtx.ast()
      case node: TerminalNode                             => node.getText
      case _                                              => throw new IllegalStateException(s"Unexpected context $ctx")
    }
    ctx.ast = ExplicitParameter(name, parameterType)(pos(ctx))
  }

  final override def exitFunctionInvocation(
    ctx: CypherParser.FunctionInvocationContext
  ): Unit = {
    val nameSpace = ctx.namespace()
    val symbolicNameString = ctx.symbolicNameString()
    val distinct = if (ctx.DISTINCT() != null) true else false
    val expressions = astSeq[Expression](ctx.expression())
    ctx.ast = FunctionInvocation(
      nameSpace.ast(),
      FunctionName(symbolicNameString.ast())(pos(symbolicNameString)),
      distinct,
      expressions
    )(pos(ctx))
  }

  final override def exitNamespace(
    ctx: CypherParser.NamespaceContext
  ): Unit = {
    ctx.ast = Namespace(astSeq[String](ctx.symbolicNameString()).toList)(pos(ctx))
  }

  final override def exitVariableList1(
    ctx: CypherParser.VariableList1Context
  ): Unit = {}

  final override def exitVariable(ctx: CypherParser.VariableContext): Unit = {
    ctx.ast = Variable(name = ctx.symbolicNameString().ast())(pos(ctx))
  }

  final override def exitSymbolicNameList1(
    ctx: CypherParser.SymbolicNameList1Context
  ): Unit = {}

  final override def exitYieldItem(
    ctx: CypherParser.YieldItemContext
  ): Unit = {}

  final override def exitYieldClause(
    ctx: CypherParser.YieldClauseContext
  ): Unit = {}

  final override def exitShowIndexesAllowBrief(
    ctx: CypherParser.ShowIndexesAllowBriefContext
  ): Unit = {}

  final override def exitShowIndexesNoBrief(
    ctx: CypherParser.ShowIndexesNoBriefContext
  ): Unit = {}

  final override def exitShowConstraintsAllowBriefAndYield(
    ctx: CypherParser.ShowConstraintsAllowBriefAndYieldContext
  ): Unit = {}

  final override def exitShowConstraintsAllowBrief(
    ctx: CypherParser.ShowConstraintsAllowBriefContext
  ): Unit = {}

  final override def exitShowConstraintsAllowYield(
    ctx: CypherParser.ShowConstraintsAllowYieldContext
  ): Unit = {}

  final override def exitShowProcedures(
    ctx: CypherParser.ShowProceduresContext
  ): Unit = {}

  final override def exitShowFunctions(
    ctx: CypherParser.ShowFunctionsContext
  ): Unit = {}

  final override def exitShowTransactions(
    ctx: CypherParser.ShowTransactionsContext
  ): Unit = {}

  final override def exitTerminateTransactions(
    ctx: CypherParser.TerminateTransactionsContext
  ): Unit = {}

  final override def exitShowSettings(
    ctx: CypherParser.ShowSettingsContext
  ): Unit = {}

  final override def exitStringsOrExpression(
    ctx: CypherParser.StringsOrExpressionContext
  ): Unit = {}

  final override def exitCreateConstraint(
    ctx: CypherParser.CreateConstraintContext
  ): Unit = {}

  final override def exitType(ctx: CypherParser.TypeContext): Unit = {
    AssertMacros.checkOnlyWhenAssertionsAreEnabled(ctx.getChildCount % 2 == 1)
    ctx.ast = ctx.children.size() match {
      case 1 => ctxChild(ctx, 0).ast
      case _ =>
        val types = ctx.typePart().asScala.map(_.ast[CypherType]()).toSet
        if (types.size == 1) types.head else ClosedDynamicUnionType(types)(pos(ctx))
    }
  }

  final override def exitTypePart(ctx: CypherParser.TypePartContext): Unit = {
    var cypherType = ctx.typeName().ast[CypherType]()
    if (ctx.typeNullability() != null) cypherType = cypherType.withIsNullable(false)
    ctx.typeListSuffix().forEach { list =>
      cypherType = ListType(cypherType, list.ast())(pos(list))
    }
    ctx.ast = cypherType
  }

  final override def exitTypeName(ctx: CypherParser.TypeNameContext): Unit = {
    val size = ctx.children.size
    val p = pos(ctx)
    val firstToken = nodeChild(ctx, 0).getSymbol.getType
    ctx.ast = size match {
      case 1 => firstToken match {
          case CypherParser.NOTHING                          => NothingType()(p)
          case CypherParser.NULL                             => NullType()(p)
          case CypherParser.BOOLEAN                          => BooleanType(true)(p)
          case CypherParser.STRING                           => StringType(true)(p)
          case CypherParser.INT | CypherParser.INTEGER       => IntegerType(true)(p)
          case CypherParser.FLOAT                            => FloatType(true)(p)
          case CypherParser.DATE                             => DateType(true)(p)
          case CypherParser.DURATION                         => DurationType(true)(p)
          case CypherParser.POINT                            => PointType(true)(p)
          case CypherParser.NODE | CypherParser.VERTEX       => NodeType(true)(p)
          case CypherParser.RELATIONSHIP | CypherParser.EDGE => RelationshipType(true)(p)
          case CypherParser.MAP                              => MapType(true)(p)
          case CypherParser.PATH                             => PathType(true)(p)
          case CypherParser.ANY                              => AnyType(true)(p)
          case _ => throw new IllegalStateException(s"Unexpected context $ctx (first token type $firstToken)")
        }
      case 2 => firstToken match {
          case CypherParser.SIGNED   => IntegerType(true)(p)
          case CypherParser.PROPERTY => PropertyValueType(true)(p)
          case CypherParser.LOCAL => nodeChild(ctx, 1).getSymbol.getType match {
              case CypherParser.TIME     => LocalTimeType(true)(p)
              case CypherParser.DATETIME => LocalDateTimeType(true)(p)
              case _ => throw new IllegalStateException(s"Unexpected context $ctx (first token type $firstToken)")
            }
          case CypherParser.ZONED => nodeChild(ctx, 1).getSymbol.getType match {
              case CypherParser.TIME     => ZonedTimeType(true)(p)
              case CypherParser.DATETIME => ZonedDateTimeType(true)(p)
              case _ => throw new IllegalStateException(s"Unexpected context $ctx (first token type $firstToken)")
            }
          case CypherParser.ANY => nodeChild(ctx, 1).getSymbol.getType match {
              case CypherParser.NODE | CypherParser.VERTEX       => NodeType(true)(p)
              case CypherParser.RELATIONSHIP | CypherParser.EDGE => RelationshipType(true)(p)
              case CypherParser.MAP                              => MapType(true)(p)
              case CypherParser.VALUE                            => AnyType(true)(p)
              case _ => throw new IllegalStateException(s"Unexpected context $ctx (first token type $firstToken)")
            }
          case _ => throw new IllegalStateException(s"Unexpected context $ctx (first token type $firstToken)")
        }
      case 3 => firstToken match {
          case CypherParser.TIME => nodeChild(ctx, 1).getSymbol.getType match {
              case CypherParser.WITH    => ZonedTimeType(true)(p)
              case CypherParser.WITHOUT => LocalTimeType(true)(p)
              case _ => throw new IllegalStateException(s"Unexpected context $ctx (first token type $firstToken)")
            }
          case CypherParser.TIMESTAMP => nodeChild(ctx, 1).getSymbol.getType match {
              case CypherParser.WITH    => ZonedDateTimeType(true)(p)
              case CypherParser.WITHOUT => LocalDateTimeType(true)(p)
              case _ => throw new IllegalStateException(s"Unexpected context $ctx (first token type $firstToken)")
            }
          case CypherParser.ANY => nodeChild(ctx, 1).getSymbol.getType match {
              case CypherParser.PROPERTY                => PropertyValueType(true)(p)
              case CypherParser.VALUE | CypherParser.LT => ctx.`type`().ast
              case _ => throw new IllegalStateException(s"Unexpected context $ctx (first token type $firstToken)")
            }
          case _ => throw new IllegalStateException(s"Unexpected context $ctx (first token type $firstToken)")
        }
      case _ => firstToken match {
          case CypherParser.LIST | CypherParser.ARRAY => ListType(ctx.`type`().ast(), true)(p)
          case CypherParser.ANY                       => ctx.`type`().ast[CypherType]()
          case _ => throw new IllegalStateException(s"Unexpected context $ctx (first token type $firstToken)")
        }
    }
  }

  final override def exitTypeNullability(ctx: CypherParser.TypeNullabilityContext): Unit = {}

  final override def exitTypeListSuffix(ctx: CypherParser.TypeListSuffixContext): Unit = {
    ctx.ast = ctx.typeNullability() == null
  }

  final override def exitLabelResource(
    ctx: CypherParser.LabelResourceContext
  ): Unit = {}

  final override def exitPropertyResource(
    ctx: CypherParser.PropertyResourceContext
  ): Unit = {}

  final override def exitGraphQualifier(
    ctx: CypherParser.GraphQualifierContext
  ): Unit = {}

  final override def exitSymbolicNameOrStringParameterList(
    ctx: CypherParser.SymbolicNameOrStringParameterListContext
  ): Unit = {}

  final override def exitSymbolicNameOrStringParameter(
    ctx: CypherParser.SymbolicNameOrStringParameterContext
  ): Unit = {}

  final override def exitStringList(
    ctx: CypherParser.StringListContext
  ): Unit = {}

  final override def exitStringOrParameter(
    ctx: CypherParser.StringOrParameterContext
  ): Unit = {}

  final override def exitMapOrParameter(
    ctx: CypherParser.MapOrParameterContext
  ): Unit = {}

  final override def exitMap(ctx: CypherParser.MapContext): Unit =
    ctx.ast = MapExpression(astPairs(ctx.propertyKeyName(), ctx.expression()))(pos(ctx))

  final override def exitSymbolicNamePositions(
    ctx: CypherParser.SymbolicNamePositionsContext
  ): Unit = {}

  final override def exitSymbolicNameString(
    ctx: CypherParser.SymbolicNameStringContext
  ): Unit = {
    ctx.ast = child[AstRuleCtx](ctx, 0).ast
  }

  final override def exitEscapedSymbolicNameString(ctx: CypherParser.EscapedSymbolicNameStringContext): Unit = {
    val textWithoutEscapes =
      ctx.start.getInputStream.getText(new Interval(ctx.start.getStartIndex + 1, ctx.stop.getStopIndex - 1))
    ctx.ast = textWithoutEscapes.replace("``", "`")
  }

  final override def exitUnescapedSymbolicNameString(
    ctx: CypherParser.UnescapedSymbolicNameStringContext
  ): Unit = {
    ctx.ast = ctx.getText
  }

  final override def exitSymbolicLabelNameString(
    ctx: CypherParser.SymbolicLabelNameStringContext
  ): Unit = {
    ctx.ast = child[AstRuleCtx](ctx, 0).ast
  }

  final override def exitUnescapedLabelSymbolicNameString(
    ctx: CypherParser.UnescapedLabelSymbolicNameStringContext
  ): Unit = {
    ctx.ast = ctx.getText
  }

  final override def exitNormalizeExpression(
    ctx: CypherParser.NormalizeExpressionContext
  ): Unit = {}

  final override def exitPatternComprehensionPrefix(
    ctx: CypherParser.PatternComprehensionPrefixContext
  ): Unit = {}

}

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
package org.neo4j.cypher.internal.frontend.phases

import org.neo4j.cypher.internal.ast.AliasedReturnItem
import org.neo4j.cypher.internal.ast.Create
import org.neo4j.cypher.internal.ast.Foreach
import org.neo4j.cypher.internal.ast.Merge
import org.neo4j.cypher.internal.ast.ReturnItems
import org.neo4j.cypher.internal.ast.SetClause
import org.neo4j.cypher.internal.ast.SingleQuery
import org.neo4j.cypher.internal.ast.Statement
import org.neo4j.cypher.internal.ast.SubqueryCall
import org.neo4j.cypher.internal.ast.Union
import org.neo4j.cypher.internal.ast.Unwind
import org.neo4j.cypher.internal.ast.UpdateClause
import org.neo4j.cypher.internal.ast.With
import org.neo4j.cypher.internal.ast.semantics.SemanticFeature
import org.neo4j.cypher.internal.ast.semantics.SemanticTable
import org.neo4j.cypher.internal.expressions.CaseExpression
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.False
import org.neo4j.cypher.internal.expressions.ListLiteral
import org.neo4j.cypher.internal.expressions.SubqueryExpression
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.frontend.phases.factories.ParsePipelineTransformerFactory
import org.neo4j.cypher.internal.rewriting.conditions.SemanticInfoAvailable
import org.neo4j.cypher.internal.rewriting.conditions.containsNoReturnAll
import org.neo4j.cypher.internal.rewriting.rewriters.LiteralExtractionStrategy
import org.neo4j.cypher.internal.util.AnonymousVariableNameGenerator
import org.neo4j.cypher.internal.util.Foldable.SkipChildren
import org.neo4j.cypher.internal.util.Foldable.TraverseChildren
import org.neo4j.cypher.internal.util.Rewriter
import org.neo4j.cypher.internal.util.StepSequencer
import org.neo4j.cypher.internal.util.symbols.ParameterTypeInfo
import org.neo4j.cypher.internal.util.topDown

import scala.collection.mutable

/**
 * Isolates subquery expressions and CASE expressions in updating clauses by placing them in a preceding WITH clause.
 * E.g.
 * {{{
 *   CREATE (a {p: COUNT { MATCH () }})
 * }}}
 * gets rewritten to
 * {{{
 *   WITH *, COUNT { MATCH () } AS anon_0
 *   CREATE (a anon_0)
 * }}}.
 *
 * Currently MERGE, SET, and FOREACH are excluded from these rewrites.
 */
case object IsolateSubqueriesInMutatingPatterns extends StatementRewriter
    with StepSequencer.Step
    with ParsePipelineTransformerFactory {

  case object SubqueriesInMutatingPatternsIsolated extends StepSequencer.Condition

  override def preConditions: Set[StepSequencer.Condition] = Set(
    BaseContains[Statement],
    BaseContains[SemanticTable]
  ) ++ SemanticInfoAvailable

  override def postConditions: Set[StepSequencer.Condition] = Set(SubqueriesInMutatingPatternsIsolated)

  override def invalidatedConditions: Set[StepSequencer.Condition] = SemanticInfoAvailable + containsNoReturnAll

  override def getTransformer(
    literalExtractionStrategy: LiteralExtractionStrategy,
    parameterTypeMapping: Map[String, ParameterTypeInfo],
    semanticFeatures: Seq[SemanticFeature],
    obfuscateLiterals: Boolean
  ): Transformer[BaseContext, BaseState, BaseState] = this

  def instance(from: BaseState, context: BaseContext): Rewriter =
    getRewriter(from.anonymousVariableNameGenerator, from.semanticTable())

  // noinspection NameBooleanParameters
  def getRewriter(
    anonymousVariableNameGenerator: AnonymousVariableNameGenerator,
    semanticTable: => SemanticTable
  ): Rewriter = {
    def rewrite(sq: SingleQuery, inSubqueryContext: Boolean): SingleQuery = {
      val clauses = sq.clauses
      val rewrittenClauses = clauses.zipWithIndex.flatMap {
        // SET should (for now) have row-by-row visibility, so we must not rewrite it
        case (set: SetClause, _) => Seq(set)
        // Subquery expressions are not allowed in MERGE and
        // should therefore not be rewritten to still get the semantic error.
        case (merge: Merge, _) => Seq(merge)
        // Foreach will need to change, but that will happen in a follow-up PR
        case (foreach: Foreach, _) => Seq(foreach)

        case (uc: UpdateClause, clauseIndex) =>
          val isFirstClause = clauseIndex == 0
          val rewrittenExpressionsMap = mutable.Map[String, Expression]()

          val rewrittenUc = uc.mapExpressions { exp =>
            case class Acc(foundSubquery: Boolean, foundCrossReferencingSubquery: Boolean)

            val Acc(foundSubquery, foundCrossReferencingSubquery) = exp.folder.treeFold(Acc(false, false)) {
              case _: CaseExpression => {
                case Acc(_, cr) => TraverseChildren(Acc(true, cr))
              }
              case se: SubqueryExpression => {
                case Acc(_, cr) =>
                  uc match {
                    // For CREATE, filter out subqueries that have dependencies on entities created in the same clause.
                    // Those are deprecated and rewriting them here would change the semantics of the query.
                    case Create(pattern) =>
                      val allSymbolDefinitions = semanticTable
                        .recordedScopes(pattern)
                        .symbolDefinitions
                        .map(_.use.value)
                      val subqueryDependsOnVariableIntroducedInCreate =
                        allSymbolDefinitions.intersect(se.scopeDependencies).nonEmpty
                      if (subqueryDependsOnVariableIntroducedInCreate) {
                        SkipChildren(Acc(true, true))
                      } else {
                        SkipChildren(Acc(true, cr))
                      }
                    case _ => SkipChildren(Acc(true, cr))
                  }
              }
              case _ => acc => TraverseChildren(acc)
            }

            if (foundSubquery & !foundCrossReferencingSubquery) {
              // Replace by a new anonymous variable
              val anonVarName = anonymousVariableNameGenerator.nextName
              rewrittenExpressionsMap += anonVarName -> exp
              Variable(anonVarName)(exp.position)
            } else {
              // Do not rewrite
              exp
            }
          }

          if (rewrittenExpressionsMap.isEmpty) {
            // Nothing to do
            Seq(uc)
          } else {
            val uselessUnwind = if (inSubqueryContext && isFirstClause) {
              // For example
              // WITH COUNT { MATCH (b) } AS `  UNNAMED1`
              // cannot be the first WITH inside CALL - it does not qualify as an importing WITH.
              // Since the original query did not have any importing WITH, we would want to place an empty
              // importing WITH in the beginning. Even if we have AST to represent an empty WITH, it would not render
              // as parseable Cypher and thus not work in Composite.
              // Therefore, we introduce a useless UNWIND, so that the following WITH is not seen as an importing WITH.
              val uselessUnwindVarName = anonymousVariableNameGenerator.nextName
              Some(Unwind(
                ListLiteral(Seq(False()(uc.position)))(uc.position),
                Variable(uselessUnwindVarName)(uc.position)
              )(uc.position))
            } else None

            // Prepend the clause with a new WITH clause
            // That projects the extracted expressions
            val withClause = With(
              ReturnItems(
                includeExisting = true,
                items = rewrittenExpressionsMap.map {
                  case (name, expression) =>
                    AliasedReturnItem(expression, Variable(name)(expression.position))(
                      expression.position
                    )
                }.toSeq
              )(uc.position)
            )(uc.position)

            uselessUnwind ++ Seq(withClause, rewrittenUc)
          }
        case (clause, _) => Seq(clause)
      }
      sq.copy(rewrittenClauses)(sq.position)
    }

    topDown(Rewriter.lift {
      // Using top-down we will rewrite the subquery call before the inner query
      case call: SubqueryCall =>
        val rewrittenQuery = call.innerQuery.mapEachSingleQuery(rewrite(_, inSubqueryContext = true))
        call.copy(innerQuery = rewrittenQuery)(call.position)
      case sq: SingleQuery => rewrite(sq, inSubqueryContext = false)
    })
  }

}

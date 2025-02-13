/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.planner.logical

import org.neo4j.cypher.internal.ast.semantics.SemanticTable
import org.neo4j.cypher.internal.compiler.planner.LogicalPlanningTestSupport2
import org.neo4j.cypher.internal.compiler.planner.logical.cardinality.CompositeExpressionSelectivityCalculator
import org.neo4j.cypher.internal.compiler.planner.logical.cardinality.IndependenceCombiner
import org.neo4j.cypher.internal.compiler.planner.logical.cardinality.assumeIndependence.AssumeIndependenceQueryGraphCardinalityModel
import org.neo4j.cypher.internal.compiler.planner.logical.steps.index.IndexCompatiblePredicatesProviderContext
import org.neo4j.cypher.internal.ir.PlannerQueryPart
import org.neo4j.cypher.internal.ir.QueryGraph
import org.neo4j.cypher.internal.ir.RegularSinglePlannerQuery
import org.neo4j.cypher.internal.ir.SinglePlannerQuery
import org.neo4j.cypher.internal.ir.UnionQuery
import org.neo4j.cypher.internal.util.Cardinality
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite

class CachedStatisticsBackedCardinalityModelTest extends CypherFunSuite with LogicalPlanningTestSupport2 {

  val config = new given()

  val selectivityCalculator: Metrics.SelectivityCalculator =
    CompositeExpressionSelectivityCalculator(
      config.planContext,
      planningTextIndexesEnabled = true,
      planningRangeIndexesEnabled = true,
      planningPointIndexesEnabled = true
    )

  val queryGraphCardinalityModel: Metrics.QueryGraphCardinalityModel =
    AssumeIndependenceQueryGraphCardinalityModel(config.planContext, selectivityCalculator, IndependenceCombiner)

  val cardinalityModel: StatisticsBackedCardinalityModel =
    new StatisticsBackedCardinalityModel(queryGraphCardinalityModel, selectivityCalculator, simpleExpressionEvaluator)

  val cachedCardinalityModel: CachedStatisticsBackedCardinalityModel =
    new CachedStatisticsBackedCardinalityModel(cardinalityModel)

  test("Calculate cardinality of a deeply nested union query without blowing the stack") {
    val depth: Int = 4_500

    def singlePlannerQuery(i: Int): SinglePlannerQuery =
      RegularSinglePlannerQuery(queryGraph = QueryGraph(patternNodes = Set(s"n_$i")))

    val plannerQueryPart = 1.until(depth).foldLeft[PlannerQueryPart](singlePlannerQuery(0)) {
      case (part, i) => UnionQuery(part, singlePlannerQuery(i), distinct = false, Nil)
    }

    cachedCardinalityModel.apply(
      plannerQueryPart = plannerQueryPart,
      labelInfo = Map.empty,
      relTypeInfo = Map.empty,
      semanticTable = SemanticTable.apply(),
      indexCompatiblePredicatesProviderContext = IndexCompatiblePredicatesProviderContext.default
    ) shouldEqual Cardinality(depth * 10_000)
  }
}

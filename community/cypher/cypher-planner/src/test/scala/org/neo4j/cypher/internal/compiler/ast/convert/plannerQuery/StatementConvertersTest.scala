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
package org.neo4j.cypher.internal.compiler.ast.convert.plannerQuery

import org.neo4j.cypher.internal.ast.AstConstructionTestSupport
import org.neo4j.cypher.internal.ast.Hint
import org.neo4j.cypher.internal.ast.Union.UnionMapping
import org.neo4j.cypher.internal.ast.UsingIndexHint
import org.neo4j.cypher.internal.compiler.planner.LogicalPlanningTestSupport
import org.neo4j.cypher.internal.compiler.planner.ProcedureCallProjection
import org.neo4j.cypher.internal.expressions.CountStar
import org.neo4j.cypher.internal.expressions.Disjoint
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.NilPathStep
import org.neo4j.cypher.internal.expressions.NodePathStep
import org.neo4j.cypher.internal.expressions.PathExpression
import org.neo4j.cypher.internal.expressions.PropertyKeyName
import org.neo4j.cypher.internal.expressions.SemanticDirection
import org.neo4j.cypher.internal.expressions.SemanticDirection.BOTH
import org.neo4j.cypher.internal.expressions.SemanticDirection.INCOMING
import org.neo4j.cypher.internal.expressions.SemanticDirection.OUTGOING
import org.neo4j.cypher.internal.expressions.Unique
import org.neo4j.cypher.internal.ir.AggregatingQueryProjection
import org.neo4j.cypher.internal.ir.CallSubqueryHorizon
import org.neo4j.cypher.internal.ir.DistinctQueryProjection
import org.neo4j.cypher.internal.ir.NodeBinding
import org.neo4j.cypher.internal.ir.PatternRelationship
import org.neo4j.cypher.internal.ir.PlannerQuery
import org.neo4j.cypher.internal.ir.Predicate
import org.neo4j.cypher.internal.ir.QuantifiedPathPattern
import org.neo4j.cypher.internal.ir.QueryGraph
import org.neo4j.cypher.internal.ir.QueryHorizon
import org.neo4j.cypher.internal.ir.QueryPagination
import org.neo4j.cypher.internal.ir.RegularQueryProjection
import org.neo4j.cypher.internal.ir.RegularSinglePlannerQuery
import org.neo4j.cypher.internal.ir.Selections
import org.neo4j.cypher.internal.ir.ShortestPathPattern
import org.neo4j.cypher.internal.ir.SimplePatternLength
import org.neo4j.cypher.internal.ir.UnionQuery
import org.neo4j.cypher.internal.ir.UnwindProjection
import org.neo4j.cypher.internal.ir.VarPatternLength
import org.neo4j.cypher.internal.ir.VariableGrouping
import org.neo4j.cypher.internal.ir.ast.ExistsIRExpression
import org.neo4j.cypher.internal.ir.ordering.InterestingOrder
import org.neo4j.cypher.internal.logical.builder.AbstractLogicalPlanBuilder.createNode
import org.neo4j.cypher.internal.logical.builder.AbstractLogicalPlanBuilder.createPattern
import org.neo4j.cypher.internal.logical.plans.FieldSignature
import org.neo4j.cypher.internal.logical.plans.ProcedureReadOnlyAccess
import org.neo4j.cypher.internal.logical.plans.ProcedureSignature
import org.neo4j.cypher.internal.logical.plans.QualifiedName
import org.neo4j.cypher.internal.util.Repetition
import org.neo4j.cypher.internal.util.UpperBound
import org.neo4j.cypher.internal.util.symbols.CTInteger
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite

import scala.collection.immutable.ListSet

class StatementConvertersTest extends CypherFunSuite with LogicalPlanningTestSupport with AstConstructionTestSupport {

  private val patternRel = PatternRelationship("r", ("a", "b"), OUTGOING, Seq.empty, SimplePatternLength)
  private val nProp = prop("n", "prop")

  test("CALL around single query") {
    val query = buildSinglePlannerQuery("CALL { RETURN 1 as x } RETURN 2 as y")
    query.horizon should equal(CallSubqueryHorizon(
      RegularSinglePlannerQuery(
        horizon = RegularQueryProjection(Map("x" -> literalInt(1)))
      ),
      correlated = false,
      yielding = true,
      inTransactionsParameters = None
    ))

    query.tail should not be empty

    val nextQuery = query.tail.get
    nextQuery.horizon should equal(RegularQueryProjection(Map("y" -> literalInt(2))))
  }

  test("CALL around single correlated query") {
    val query = buildSinglePlannerQuery("WITH 1 AS x CALL { WITH x RETURN x as y } RETURN y")

    query.horizon should equal(RegularQueryProjection(Map("x" -> literalInt(1))))

    query.tail should not be empty
    val subQuery = query.tail.get

    subQuery.horizon should equal(
      CallSubqueryHorizon(
        correlated = true,
        yielding = true,
        inTransactionsParameters = None,
        callSubquery = RegularSinglePlannerQuery(
          queryGraph = QueryGraph(argumentIds = Set("x")),
          horizon = RegularQueryProjection(Map("y" -> varFor("x")))
        )
      )
    )

    subQuery.tail should not be empty
    val nextQuery = subQuery.tail.get

    nextQuery.horizon should equal(RegularQueryProjection(Map("y" -> varFor("y"))))
  }

  test("CALL around single query - using returned var in outer query") {
    val query = buildSinglePlannerQuery("CALL { RETURN 1 as x } RETURN x")
    query.horizon should equal(CallSubqueryHorizon(
      RegularSinglePlannerQuery(
        horizon = RegularQueryProjection(Map("x" -> literalInt(1)))
      ),
      correlated = false,
      yielding = true,
      inTransactionsParameters = None
    ))

    query.tail should not be empty

    val nextQuery = query.tail.get
    nextQuery.horizon should equal(RegularQueryProjection(Map("x" -> varFor("x"))))
  }

  test("CALL around union query") {
    val query = buildSinglePlannerQuery("CALL { RETURN 1 as x UNION RETURN 2 as x } RETURN 3 as y")
    query.horizon should equal(CallSubqueryHorizon(
      UnionQuery(
        RegularSinglePlannerQuery(
          horizon = RegularQueryProjection(Map("x" -> literalInt(1)))
        ),
        RegularSinglePlannerQuery(
          horizon = RegularQueryProjection(Map("x" -> literalInt(2)))
        ),
        distinct = true,
        List(UnionMapping(varFor("x"), varFor("x"), varFor("x")))
      ),
      correlated = false,
      yielding = true,
      inTransactionsParameters = None
    ))

    query.tail should not be empty

    val nextQuery = query.tail.get
    nextQuery.horizon should equal(RegularQueryProjection(Map("y" -> literalInt(3))))
  }

  test("CALL around correlated union query") {
    val query =
      buildSinglePlannerQuery("WITH 1 AS x, 2 AS n CALL { WITH x RETURN x as y UNION RETURN 2 as y } RETURN y")

    query.horizon should equal(RegularQueryProjection(Map("x" -> literalInt(1), "n" -> literalInt(2))))

    query.tail should not be empty

    val subquery = query.tail.get
    subquery.horizon should equal(CallSubqueryHorizon(
      correlated = true,
      yielding = true,
      inTransactionsParameters = None,
      callSubquery = UnionQuery(
        RegularSinglePlannerQuery(
          queryGraph = QueryGraph(argumentIds = Set("x")),
          horizon = RegularQueryProjection(Map("y" -> varFor("x")))
        ),
        RegularSinglePlannerQuery(
          horizon = RegularQueryProjection(Map("y" -> literalInt(2)))
        ),
        distinct = true,
        List(UnionMapping(varFor("y"), varFor("y"), varFor("y")))
      )
    ))

    subquery.tail should not be empty

    val nextQuery = subquery.tail.get
    nextQuery.horizon should equal(RegularQueryProjection(Map("y" -> varFor("y"))))
  }

  test("CALL around union query - using returned var in outer query") {
    val query = buildSinglePlannerQuery("CALL { RETURN 1 as x UNION RETURN 2 as x } RETURN x")
    query.horizon should equal(CallSubqueryHorizon(
      UnionQuery(
        RegularSinglePlannerQuery(
          horizon = RegularQueryProjection(Map("x" -> literalInt(1)))
        ),
        RegularSinglePlannerQuery(
          horizon = RegularQueryProjection(Map("x" -> literalInt(2)))
        ),
        distinct = true,
        List(UnionMapping(varFor("x"), varFor("x"), varFor("x")))
      ),
      correlated = false,
      yielding = true,
      inTransactionsParameters = None
    ))

    query.tail should not be empty

    val nextQuery = query.tail.get
    nextQuery.horizon should equal(RegularQueryProjection(Map("x" -> varFor("x"))))
  }

  test("CALL unit subquery in transactions") {
    val query = buildSinglePlannerQuery("CALL { CREATE (x) } IN TRANSACTIONS RETURN 2 as y")
    query.horizon shouldEqual CallSubqueryHorizon(
      RegularSinglePlannerQuery(queryGraph = QueryGraph.empty.addMutatingPatterns(createPattern(Seq(createNode("x"))))),
      correlated = false,
      yielding = false,
      inTransactionsParameters = Some(inTransactionsParameters(None, None, None))
    )

    query.tail should not be empty

    val nextQuery = query.tail.get
    nextQuery.horizon should equal(RegularQueryProjection(Map("y" -> literalInt(2))))
  }

  test("CALL correlated unit subquery in transactions") {
    val query = buildSinglePlannerQuery("WITH 1 AS n CALL { WITH n CREATE (x) } IN TRANSACTIONS RETURN 2 as y")

    query.horizon shouldEqual RegularQueryProjection(Map("n" -> literalInt(1)))

    query.tail should not be empty
    val subQuery = query.tail.get

    subQuery.horizon shouldEqual CallSubqueryHorizon(
      RegularSinglePlannerQuery(queryGraph =
        QueryGraph.empty
          .addMutatingPatterns(createPattern(Seq(createNode("x"))))
          .addArgumentId("n")
      ),
      correlated = true,
      yielding = false,
      inTransactionsParameters = Some(inTransactionsParameters(None, None, None))
    )

    subQuery.tail should not be empty

    val nextQuery = subQuery.tail.get
    nextQuery.horizon should equal(RegularQueryProjection(Map("y" -> literalInt(2))))
  }

  test("CALL subquery in transactions") {
    val query = buildSinglePlannerQuery("CALL { CREATE (x) RETURN x } IN TRANSACTIONS RETURN 2 as y")
    query.horizon shouldEqual CallSubqueryHorizon(
      RegularSinglePlannerQuery(
        queryGraph = QueryGraph.empty.addMutatingPatterns(createPattern(Seq(createNode("x")))),
        horizon = RegularQueryProjection(Map("x" -> varFor("x")))
      ),
      correlated = false,
      yielding = true,
      inTransactionsParameters = Some(inTransactionsParameters(None, None, None))
    )

    query.tail should not be empty

    val nextQuery = query.tail.get
    nextQuery.horizon should equal(RegularQueryProjection(Map("y" -> literalInt(2))))
  }

  test("CALL correlated subquery in transactions") {
    val query = buildSinglePlannerQuery("WITH 1 AS n CALL { WITH n CREATE (x) RETURN x } IN TRANSACTIONS RETURN 2 as y")

    query.horizon shouldEqual RegularQueryProjection(Map("n" -> literalInt(1)))

    query.tail should not be empty
    val subQuery = query.tail.get

    subQuery.horizon shouldEqual CallSubqueryHorizon(
      RegularSinglePlannerQuery(
        horizon = RegularQueryProjection(Map("x" -> varFor("x"))),
        queryGraph = QueryGraph.empty
          .addMutatingPatterns(createPattern(Seq(createNode("x"))))
          .addArgumentId("n")
      ),
      correlated = true,
      yielding = true,
      inTransactionsParameters = Some(inTransactionsParameters(None, None, None))
    )

    subQuery.tail should not be empty

    val nextQuery = subQuery.tail.get
    nextQuery.horizon should equal(RegularQueryProjection(Map("y" -> literalInt(2))))
  }

  test("RETURN 42") {
    val query = buildSinglePlannerQuery("RETURN 42")
    query.horizon should equal(RegularQueryProjection(Map("42" -> literalInt(42))))
  }

  test("RETURN 42, 'foo'") {
    val query = buildSinglePlannerQuery("RETURN 42, 'foo'")
    query.horizon should equal(RegularQueryProjection(Map(
      "42" -> literalInt(42),
      "'foo'" -> literalString("foo")
    )))
  }

  test("match (n) return n") {
    val query = buildSinglePlannerQuery("match (n) return n")
    query.horizon should equal(RegularQueryProjection(Map(
      "n" -> varFor("n")
    )))

    query.queryGraph.patternNodes should equal(Set("n"))
  }

  test("MATCH (n) WHERE n:A:B RETURN n") {
    val query = buildSinglePlannerQuery("MATCH (n) WHERE n:A:B RETURN n")
    query.horizon should equal(RegularQueryProjection(Map(
      "n" -> varFor("n")
    )))

    query.queryGraph.selections should equal(Selections(Set(
      Predicate(Set("n"), hasLabels("n", "A")),
      Predicate(Set("n"), hasLabels("n", "B"))
    )))

    query.queryGraph.patternNodes should equal(Set("n"))
  }

  test("match (n) where n:X OR n:Y return n") {
    val query = buildSinglePlannerQuery("match (n) where n:X OR n:Y return n")
    query.horizon should equal(RegularQueryProjection(Map(
      "n" -> varFor("n")
    )))

    query.queryGraph.selections should equal(Selections(Set(
      Predicate(
        Set("n"),
        ors(
          hasLabels("n", "X"),
          hasLabels("n", "Y")
        )
      )
    )))

    query.queryGraph.patternNodes should equal(Set("n"))
  }

  test("MATCH (n) WHERE n:X OR (n:A AND n:B) RETURN n") {
    val query = buildSinglePlannerQuery("MATCH (n) WHERE n:X OR (n:A AND n:B) RETURN n")
    query.horizon should equal(RegularQueryProjection(Map(
      "n" -> varFor("n")
    )))

    query.queryGraph.selections should equal(Selections(Set(
      Predicate(
        Set("n"),
        ors(
          hasLabels("n", "X"),
          hasLabels("n", "B")
        )
      ),
      Predicate(
        Set("n"),
        ors(
          hasLabels("n", "X"),
          hasLabels("n", "A")
        )
      )
    )))

    query.queryGraph.patternNodes should equal(Set("n"))
  }

  test("MATCH (n) WHERE id(n) = 42 RETURN n") {
    val query = buildSinglePlannerQuery("MATCH (n) WHERE id(n) = 42 RETURN n")
    query.horizon should equal(RegularQueryProjection(Map(
      "n" -> varFor("n")
    )))

    query.queryGraph.selections should equal(Selections(Set(
      Predicate(
        Set("n"),
        in(
          id(varFor("n")),
          listOfInt(42)
        )
      )
    )))

    query.queryGraph.patternNodes should equal(Set("n"))
  }

  test("MATCH (n) WHERE id(n) IN [42, 43] RETURN n") {
    val query = buildSinglePlannerQuery("MATCH (n) WHERE id(n) IN [42, 43] RETURN n")
    query.horizon should equal(RegularQueryProjection(Map(
      "n" -> varFor("n")
    )))

    query.queryGraph.selections should equal(Selections(Set(
      Predicate(
        Set("n"),
        in(
          id(varFor("n")),
          listOfInt(42, 43)
        )
      )
    )))

    query.queryGraph.patternNodes should equal(Set("n"))
  }

  test("MATCH (n) WHERE n:A AND id(n) = 42 RETURN n") {
    val query = buildSinglePlannerQuery("MATCH (n) WHERE n:A AND id(n) = 42 RETURN n")
    query.horizon should equal(RegularQueryProjection(Map(
      "n" -> varFor("n")
    )))

    query.queryGraph.selections should equal(Selections(Set(
      Predicate(Set("n"), hasLabels("n", "A")),
      Predicate(
        Set("n"),
        in(
          id(varFor("n")),
          listOfInt(42)
        )
      )
    )))

    query.queryGraph.patternNodes should equal(Set("n"))
  }

  test("match p = (a) return p") {
    val query = buildSinglePlannerQuery("match p = (a) return p")
    query.queryGraph.patternRelationships should equal(Set())
    query.queryGraph.patternNodes should equal(Set[String]("a"))
    query.queryGraph.selections should equal(Selections(Set.empty))
    query.horizon should equal(RegularQueryProjection(Map[String, Expression](
      "p" -> PathExpression(NodePathStep(varFor("a"), NilPathStep()(pos))(pos)) _
    )))
  }

  test("match p = (a)-[r]->(b) return a,r") {
    val query = buildSinglePlannerQuery("match p = (a)-[r]->(b) return a,r")
    query.queryGraph.patternRelationships should equal(Set(patternRel))
    query.queryGraph.patternNodes should equal(Set[String]("a", "b"))
    query.queryGraph.selections should equal(Selections(Set.empty))
    query.horizon should equal(RegularQueryProjection(Map(
      "a" -> varFor("a"),
      "r" -> varFor("r")
    )))
  }

  test("match (a)-[r]->(b)-[r2]->(c) return a,r,b, c") {
    val query = buildSinglePlannerQuery("match (a)-[r]->(b)-[r2]->(c) return a,r,b")
    query.queryGraph.patternRelationships should equal(Set(
      PatternRelationship("r", ("a", "b"), OUTGOING, Seq.empty, SimplePatternLength),
      PatternRelationship("r2", ("b", "c"), OUTGOING, Seq.empty, SimplePatternLength)
    ))
    query.queryGraph.patternNodes should equal(Set("a", "b", "c"))
    query.queryGraph.selections should equal(Selections.from(not(equals(varFor("r2"), varFor("r")))))
    query.horizon should equal(RegularQueryProjection(Map(
      "a" -> varFor("a"),
      "r" -> varFor("r"),
      "b" -> varFor("b")
    )))
  }

  test("match (a)-[r]->(b)-[r2]->(a) return a,r") {
    val query = buildSinglePlannerQuery("match (a)-[r]->(b)-[r2]->(a) return a,r")
    query.queryGraph.patternRelationships should equal(Set(
      PatternRelationship("r", ("a", "b"), OUTGOING, Seq.empty, SimplePatternLength),
      PatternRelationship("r2", ("b", "a"), OUTGOING, Seq.empty, SimplePatternLength)
    ))
    query.queryGraph.patternNodes should equal(Set("a", "b"))
    query.queryGraph.selections should equal(Selections.from(not(equals(varFor("r2"), varFor("r")))))
    query.horizon should equal(RegularQueryProjection(Map(
      "a" -> varFor("a"),
      "r" -> varFor("r")
    )))
  }

  test("match (a)<-[r]-(b)-[r2]-(c) return a,r") {
    val query = buildSinglePlannerQuery("match (a)<-[r]-(b)-[r2]-(c) return a,r")
    query.queryGraph.patternRelationships should equal(Set(
      PatternRelationship("r", ("a", "b"), INCOMING, Seq.empty, SimplePatternLength),
      PatternRelationship("r2", ("b", "c"), BOTH, Seq.empty, SimplePatternLength)
    ))
    query.queryGraph.patternNodes should equal(Set("a", "b", "c"))
    query.queryGraph.selections should equal(Selections.from(not(equals(varFor("r2"), varFor("r")))))
    query.horizon should equal(RegularQueryProjection(Map(
      "a" -> varFor("a"),
      "r" -> varFor("r")
    )))
  }

  test("match (a)<-[r]-(b), (b)-[r2]-(c) return a,r") {
    val query = buildSinglePlannerQuery("match (a)<-[r]-(b), (b)-[r2]-(c) return a,r")
    query.queryGraph.patternRelationships should equal(Set(
      PatternRelationship("r", ("a", "b"), INCOMING, Seq.empty, SimplePatternLength),
      PatternRelationship("r2", ("b", "c"), BOTH, Seq.empty, SimplePatternLength)
    ))
    query.queryGraph.patternNodes should equal(Set("a", "b", "c"))
    val predicate = Predicate(Set("r", "r2"), not(equals(varFor("r"), varFor("r2"))))
    query.queryGraph.selections.predicates should equal(Set(predicate))
    query.horizon should equal(RegularQueryProjection(Map(
      "a" -> varFor("a"),
      "r" -> varFor("r")
    )))
  }

  test("match (a), (n)-[r:Type]-(c) where b:A return a,r") {
    val query = buildSinglePlannerQuery("match (a), (n)-[r:Type]-(c) where n:A return a,r")
    query.queryGraph.patternRelationships should equal(Set(
      PatternRelationship("r", ("n", "c"), BOTH, Seq(relTypeName("Type")), SimplePatternLength)
    ))
    query.queryGraph.patternNodes should equal(Set("a", "n", "c"))
    query.queryGraph.selections should equal(Selections(Set(
      Predicate(Set("n"), hasLabels("n", "A"))
    )))
    query.horizon should equal(RegularQueryProjection(Map(
      "a" -> varFor("a"),
      "r" -> varFor("r")
    )))
  }

  test("match (a)-[r:Type|Foo]-(b) return a,r") {
    val query = buildSinglePlannerQuery("match (a)-[r:Type|Foo]-(b) return a,r")
    query.queryGraph.patternRelationships should equal(Set(
      PatternRelationship("r", ("a", "b"), BOTH, Seq(relTypeName("Type"), relTypeName("Foo")), SimplePatternLength)
    ))
    query.queryGraph.patternNodes should equal(Set("a", "b"))
    query.queryGraph.selections should equal(Selections())
    query.horizon should equal(RegularQueryProjection(Map(
      "a" -> varFor("a"),
      "r" -> varFor("r")
    )))
  }

  test("match (a)-[r:Type*]-(b) return a,r") {
    val query = buildSinglePlannerQuery("match (a)-[r:Type*]-(b) return a,r")
    query.queryGraph.patternRelationships should equal(Set(
      PatternRelationship("r", ("a", "b"), BOTH, Seq(relTypeName("Type")), VarPatternLength(1, None))
    ))
    query.queryGraph.patternNodes should equal(Set("a", "b"))
    query.queryGraph.selections should equal(Selections.from(Unique(varFor("r"))(pos)))
    query.horizon should equal(RegularQueryProjection(Map(
      "a" -> varFor("a"),
      "r" -> varFor("r")
    )))
  }

  test("match (a)-[r1:CONTAINS*0..1]->(b)-[r2:FRIEND*0..1]->(c) return a,b,c") {
    val query = buildSinglePlannerQuery("match (a)-[r1:CONTAINS*0..1]->(b)-[r2:FRIEND*0..1]->(c) return a,b,c")
    query.queryGraph.patternRelationships should equal(Set(
      PatternRelationship("r1", ("a", "b"), OUTGOING, Seq(relTypeName("CONTAINS")), VarPatternLength(0, Some(1))),
      PatternRelationship("r2", ("b", "c"), OUTGOING, Seq(relTypeName("FRIEND")), VarPatternLength(0, Some(1)))
    ))
    query.queryGraph.patternNodes should equal(Set("a", "b", "c"))
    query.queryGraph.selections should equal(Selections.from(Seq(Unique(varFor("r1"))(pos), Unique(varFor("r2"))(pos))))
    query.horizon should equal(RegularQueryProjection(Map(
      "a" -> varFor("a"),
      "b" -> varFor("b"),
      "c" -> varFor("c")
    )))
  }

  test("match (a)-[r:Type*3..]-(b) return a,r") {
    val query = buildSinglePlannerQuery("match (a)-[r:Type*3..]-(b) return a,r")
    query.queryGraph.patternRelationships should equal(Set(
      PatternRelationship("r", ("a", "b"), BOTH, Seq(relTypeName("Type")), VarPatternLength(3, None))
    ))
    query.queryGraph.patternNodes should equal(Set("a", "b"))
    query.queryGraph.selections should equal(Selections.from(Unique(varFor("r"))(pos)))
    query.horizon should equal(RegularQueryProjection(Map(
      "a" -> varFor("a"),
      "r" -> varFor("r")
    )))
  }

  test("match (a)-[r:Type*5]-(b) return a,r") {
    val query = buildSinglePlannerQuery("match (a)-[r:Type*5]-(b) return a,r")
    query.queryGraph.patternRelationships should equal(Set(
      PatternRelationship("r", ("a", "b"), BOTH, Seq(relTypeName("Type")), VarPatternLength.fixed(5))
    ))
    query.queryGraph.patternNodes should equal(Set("a", "b"))
    query.queryGraph.selections should equal(Selections.from(Unique(varFor("r"))(pos)))
    query.horizon should equal(RegularQueryProjection(Map(
      "a" -> varFor("a"),
      "r" -> varFor("r")
    )))
  }

  test("match (a)<-[r*]-(b)-[r2*]-(c) return a,r") {
    val query = buildSinglePlannerQuery("match (a)<-[r*]-(b)-[r2*]-(c) return a,r")
    query.queryGraph.patternRelationships should equal(Set(
      PatternRelationship("r", ("a", "b"), INCOMING, Seq.empty, VarPatternLength(1, None)),
      PatternRelationship("r2", ("b", "c"), BOTH, Seq.empty, VarPatternLength(1, None))
    ))
    query.queryGraph.patternNodes should equal(Set("a", "b", "c"))

    query.queryGraph.selections should equal(Selections.from(Seq(
      Unique(varFor("r"))(pos),
      Unique(varFor("r2"))(pos),
      Disjoint(varFor("r2"), varFor("r"))(pos)
    )))
    query.horizon should equal(RegularQueryProjection(Map(
      "a" -> varFor("a"),
      "r" -> varFor("r")
    )))
  }

  test("optional match (a) return a") {
    val query = buildSinglePlannerQuery("optional match (a) return a")
    query.queryGraph.patternRelationships should equal(Set())
    query.queryGraph.patternNodes should equal(Set())
    query.queryGraph.selections should equal(Selections(Set.empty))
    query.horizon should equal(RegularQueryProjection(Map(
      "a" -> varFor("a")
    )))

    query.queryGraph.optionalMatches.size should equal(1)
    query.queryGraph.argumentIds should equal(Set())

    val optMatchQG = query.queryGraph.optionalMatches.head
    optMatchQG.patternRelationships should equal(Set())
    optMatchQG.patternNodes should equal(Set("a"))
    optMatchQG.selections should equal(Selections(Set.empty))
    optMatchQG.optionalMatches should be(empty)
    optMatchQG.argumentIds should equal(Set())
  }

  test("optional match (a)-[r]->(b) return a,b,r") {
    val query = buildSinglePlannerQuery("optional match (a)-[r]->(b) return a,b,r")
    query.queryGraph.patternRelationships should equal(Set())
    query.queryGraph.patternNodes should equal(Set())
    query.queryGraph.selections should equal(Selections(Set.empty))
    query.horizon should equal(RegularQueryProjection(Map(
      "a" -> varFor("a"),
      "b" -> varFor("b"),
      "r" -> varFor("r")
    )))

    query.queryGraph.optionalMatches.size should equal(1)
    query.queryGraph.argumentIds should equal(Set())

    val optMatchQG = query.queryGraph.optionalMatches.head
    optMatchQG.patternRelationships should equal(Set(
      PatternRelationship("r", ("a", "b"), OUTGOING, Seq.empty, SimplePatternLength)
    ))
    optMatchQG.patternNodes should equal(Set("a", "b"))
    optMatchQG.selections should equal(Selections(Set.empty))
    optMatchQG.optionalMatches should be(empty)
    optMatchQG.argumentIds should equal(Set())
  }

  test("match (a) optional match (a)-[r]->(b) return a,b,r") {
    val query = buildSinglePlannerQuery("match (a) optional match (a)-[r]->(b) return a,b,r")
    query.queryGraph.patternNodes should equal(Set("a"))
    query.queryGraph.patternRelationships should equal(Set())
    query.queryGraph.selections should equal(Selections(Set.empty))
    query.horizon should equal(RegularQueryProjection(Map(
      "a" -> varFor("a"),
      "b" -> varFor("b"),
      "r" -> varFor("r")
    )))
    query.queryGraph.optionalMatches.size should equal(1)
    query.queryGraph.argumentIds should equal(Set())

    val optMatchQG = query.queryGraph.optionalMatches.head
    optMatchQG.patternNodes should equal(Set("a", "b"))
    optMatchQG.patternRelationships should equal(Set(
      PatternRelationship("r", ("a", "b"), OUTGOING, Seq.empty, SimplePatternLength)
    ))
    optMatchQG.selections should equal(Selections(Set.empty))
    optMatchQG.optionalMatches should be(empty)
    optMatchQG.argumentIds should equal(Set("a"))
  }

  test("match (a) where (a)-->() return a") {
    // Given
    val query = buildSinglePlannerQuery("match (a) where (a)-->() return a")

    // Then inner pattern query graph
    val relName = "anon_0"
    val nodeName = "anon_1"
    val existsVariableName = "anon_2"
    val exp = ExistsIRExpression(
      PlannerQuery(
        RegularSinglePlannerQuery(
          QueryGraph(
            argumentIds = Set("a"),
            patternNodes = Set("a", nodeName),
            patternRelationships =
              Set(PatternRelationship(relName, ("a", nodeName), OUTGOING, Seq(), SimplePatternLength))
          )
        )
      ),
      existsVariableName,
      s"EXISTS { MATCH (a)-[`$relName`]->(`$nodeName`) }"
    )(pos)
    val predicate = Predicate(Set("a"), exp)
    val selections = Selections(Set(predicate))

    query.queryGraph.selections should equal(selections)
    query.queryGraph.patternNodes should equal(Set("a"))
  }

  test("MATCH (a) WITH 1 AS b RETURN b") {
    val query = buildSinglePlannerQuery("MATCH (a) WITH 1 AS b RETURN b")
    query.queryGraph.patternNodes should equal(Set("a"))
    query.horizon should equal(RegularQueryProjection(Map("b" -> literalInt(1))))
    query.tail should equal(Some(RegularSinglePlannerQuery(
      QueryGraph(argumentIds = Set("b")),
      InterestingOrder.empty,
      RegularQueryProjection(Map("b" -> varFor("b")))
    )))
  }

  test("WITH 1 AS b RETURN b") {
    val query = buildSinglePlannerQuery("WITH 1 AS b RETURN b")

    query.horizon should equal(RegularQueryProjection(Map("b" -> literalInt(1))))
    query.tail should equal(Some(RegularSinglePlannerQuery(
      QueryGraph(argumentIds = Set("b")),
      InterestingOrder.empty,
      RegularQueryProjection(Map("b" -> varFor("b")))
    )))
  }

  test("MATCH (a) WITH a WHERE TRUE RETURN a") {
    val result = buildSinglePlannerQuery("MATCH (a) WITH a WHERE TRUE RETURN a")

    val expectation = RegularSinglePlannerQuery(
      queryGraph = QueryGraph(patternNodes = Set("a"), selections = Selections(Set(Predicate(Set.empty, trueLiteral)))),
      horizon = RegularQueryProjection(projections = Map("a" -> varFor("a")))
    )

    result should equal(expectation)
  }

  test("match (a) where a.prop = 42 OR (a)-->() return a") {
    // Given
    val query = buildSinglePlannerQuery("match (a) where a.prop = 42 OR (a)-->() return a")

    // Then inner pattern query graph
    val relName = "anon_0"
    val nodeName = "anon_1"
    val existsVariableName = "anon_2"

    val exp1 = ExistsIRExpression(
      PlannerQuery(
        RegularSinglePlannerQuery(
          QueryGraph(
            argumentIds = Set("a"),
            patternNodes = Set("a", nodeName),
            patternRelationships =
              Set(PatternRelationship(relName, ("a", nodeName), OUTGOING, Seq(), SimplePatternLength))
          )
        )
      ),
      existsVariableName,
      s"EXISTS { MATCH (a)-[`$relName`]->(`$nodeName`) }"
    )(pos)

    val exp2 = in(prop("a", "prop"), listOfInt(42))
    val orPredicate = Predicate(Set("a"), ors(exp1, exp2))
    val selections = Selections(Set(orPredicate))

    query.queryGraph.selections should equal(selections)
    query.queryGraph.patternNodes should equal(Set("a"))
  }

  test("match (a) where (a)-->() OR a.prop = 42 return a") {
    // Given
    val query = buildSinglePlannerQuery("match (a) where (a)-->() OR a.prop = 42 return a")

    // Then inner pattern query graph
    val relName = "anon_0"
    val nodeName = "anon_1"
    val existsVariableName = "anon_2"

    val exp1 = ExistsIRExpression(
      PlannerQuery(
        RegularSinglePlannerQuery(
          QueryGraph(
            argumentIds = Set("a"),
            patternNodes = Set("a", nodeName),
            patternRelationships =
              Set(PatternRelationship(relName, ("a", nodeName), OUTGOING, Seq(), SimplePatternLength))
          )
        )
      ),
      existsVariableName,
      s"EXISTS { MATCH (a)-[`$relName`]->(`$nodeName`) }"
    )(pos)

    val exp2 = in(prop("a", "prop"), listOfInt(42))
    val orPredicate = Predicate(Set("a"), ors(exp1, exp2))
    val selections = Selections(Set(orPredicate))

    query.queryGraph.selections should equal(selections)
    query.queryGraph.patternNodes should equal(Set("a"))
  }

  test("match (a) where a.prop2 = 21 OR (a)-->() OR a.prop = 42 return a") {
    // Given
    val query = buildSinglePlannerQuery("match (a) where a.prop2 = 21 OR (a)-->() OR a.prop = 42 return a")

    // Then inner pattern query graph
    val relName = "anon_0"
    val nodeName = "anon_1"
    val existsVariableName = "anon_2"

    val exp1 = ExistsIRExpression(
      PlannerQuery(
        RegularSinglePlannerQuery(
          QueryGraph(
            argumentIds = Set("a"),
            patternNodes = Set("a", nodeName),
            patternRelationships =
              Set(PatternRelationship(relName, ("a", nodeName), OUTGOING, Seq(), SimplePatternLength))
          )
        )
      ),
      existsVariableName,
      s"EXISTS { MATCH (a)-[`$relName`]->(`$nodeName`) }"
    )(pos)

    val exp2 = in(prop("a", "prop"), listOfInt(42))
    val exp3 = in(prop("a", "prop2"), listOfInt(21))
    val orPredicate = Predicate(Set("a"), ors(exp1, exp2, exp3))

    val selections = Selections(Set(orPredicate))

    query.queryGraph.selections should equal(selections)
    query.queryGraph.patternNodes should equal(Set("a"))
  }

  test("match (n) return n limit 10") {
    // Given
    val query = buildSinglePlannerQuery("match (n) return n limit 10")

    // Then inner pattern query graph
    query.queryGraph.selections should equal(Selections())
    query.queryGraph.patternNodes should equal(Set("n"))
    query.horizon should equal(
      RegularQueryProjection(
        projections = Map("n" -> varFor("n")),
        QueryPagination(
          skip = None,
          limit = Some(literalInt(10))
        )
      )
    )
  }

  test("match (n) return n skip 10") {
    // Given
    val query = buildSinglePlannerQuery("match (n) return n skip 10")

    // Then inner pattern query graph
    query.queryGraph.selections should equal(Selections())
    query.queryGraph.patternNodes should equal(Set("n"))
    query.horizon should equal(
      RegularQueryProjection(
        projections = Map("n" -> varFor("n")),
        QueryPagination(
          skip = Some(literalInt(10)),
          limit = None
        )
      )
    )
  }

  test("match (a) with * return a") {
    val query = buildSinglePlannerQuery("match (a) with * return a")
    query.queryGraph.patternNodes should equal(Set("a"))
    query.horizon should equal(RegularQueryProjection(Map[String, Expression]("a" -> varFor("a"))))
    query.tail should equal(None)
  }

  test("MATCH (a) WITH a LIMIT 1 MATCH (a)-[r]->(b) RETURN a, b") {
    val query = buildSinglePlannerQuery("MATCH (a) WITH a LIMIT 1 MATCH (a)-[r]->(b) RETURN a, b")
    query.queryGraph should equal(
      QueryGraph
        .empty
        .addPatternNodes("a")
    )

    query.horizon should equal(
      RegularQueryProjection(
        projections = Map("a" -> varFor("a")),
        QueryPagination(
          skip = None,
          limit = Some(literalInt(1))
        )
      )
    )

    query.tail should not be empty
    query.tail.get.queryGraph should equal(
      QueryGraph
        .empty
        .addArgumentIds(Seq("a"))
        .addPatternNodes("a", "b")
        .addPatternRelationship(patternRel)
    )
  }

  test("optional match (a:Foo) with a match (a)-[r]->(b) return a") {
    val query = buildSinglePlannerQuery("optional match (a:Foo) with a match (a)-[r]->(b) return a")

    query.queryGraph should equal(
      QueryGraph
        .empty
        .withAddedOptionalMatch(
          QueryGraph
            .empty
            .addPatternNodes("a")
            .addSelections(Selections(Set(Predicate(Set("a"), hasLabels("a", "Foo")))))
        )
    )
    query.horizon should equal(RegularQueryProjection(Map("a" -> varFor("a"))))
    query.tail should not be empty
    query.tail.get.queryGraph should equal(
      QueryGraph
        .empty
        .addArgumentIds(Seq("a"))
        .addPatternNodes("a", "b")
        .addPatternRelationship(patternRel)
    )
  }

  test("MATCH (a:Start) WITH a.prop AS property LIMIT 1 MATCH (b) WHERE id(b) = property RETURN b") {
    val query = buildSinglePlannerQuery(
      "MATCH (a:Start) WITH a.prop AS property LIMIT 1 MATCH (b) WHERE id(b) = property RETURN b"
    )
    query.tail should not be empty
    query.queryGraph.selections.predicates should equal(Set(
      Predicate(Set("a"), hasLabels("a", "Start"))
    ))
    query.queryGraph.patternNodes should equal(Set("a"))
    query.horizon should equal(
      RegularQueryProjection(
        Map("property" -> prop("a", "prop")),
        QueryPagination(None, Some(literalInt(1)))
      )
    )
    val tailQg = query.tail.get
    tailQg.queryGraph.patternNodes should equal(Set("b"))
    tailQg.queryGraph.patternRelationships should be(empty)

    tailQg.queryGraph.selections.predicates should equal(Set(
      Predicate(
        Set("b", "property"),
        in(id(varFor("b")), listOf(varFor("property")))
      )
    ))

    tailQg.horizon should equal(
      RegularQueryProjection(
        projections = Map("b" -> varFor("b")),
        QueryPagination()
      )
    )
  }

  test("MATCH (a:Start) WITH a.prop AS property MATCH (b) WHERE id(b) = property RETURN b") {
    val query =
      buildSinglePlannerQuery("MATCH (a:Start) WITH a.prop AS property MATCH (b) WHERE id(b) = property RETURN b")
    query.queryGraph.patternNodes should equal(Set("a"))
    query.queryGraph.selections.predicates should equal(Set(
      Predicate(Set("a"), hasLabels("a", "Start"))
    ))

    query.horizon should equal(
      RegularQueryProjection(
        Map("property" -> prop("a", "prop"))
      )
    )

    val secondQuery = query.tail.get
    secondQuery.queryGraph.selections.predicates should equal(Set(
      Predicate(
        Set("b", "property"),
        in(id(varFor("b")), listOf(varFor("property")))
      )
    ))

    secondQuery.horizon should equal(
      RegularQueryProjection(
        Map("b" -> varFor("b"))
      )
    )
  }

  test("MATCH (a:Start) WITH a.prop AS property, count(*) AS count MATCH (b) WHERE id(b) = property RETURN b") {
    val query = buildSinglePlannerQuery(
      "MATCH (a:Start) WITH a.prop AS property, count(*) AS count MATCH (b) WHERE id(b) = property RETURN b"
    )
    query.tail should not be empty
    query.queryGraph.selections.predicates should equal(Set(
      Predicate(Set("a"), hasLabels("a", "Start"))
    ))
    query.queryGraph.patternNodes should equal(Set("a"))
    query.horizon should equal(AggregatingQueryProjection(
      Map("property" -> prop("a", "prop")),
      Map("count" -> CountStar() _)
    ))

    val tailQg = query.tail.get
    tailQg.queryGraph.patternNodes should equal(Set("b"))
    tailQg.queryGraph.patternRelationships should be(empty)
    tailQg.queryGraph.selections.predicates should equal(Set(
      Predicate(
        Set("b", "property"),
        in(id(varFor("b")), listOf(varFor("property")))
      )
    ))

    tailQg.horizon should equal(
      RegularQueryProjection(
        projections = Map("b" -> varFor("b")),
        QueryPagination()
      )
    )
  }

  test("MATCH (n) RETURN count(*)") {
    val query = buildSinglePlannerQuery("MATCH (n) RETURN count(*)")

    query.horizon match {
      case AggregatingQueryProjection(groupingKeys, aggregationExpression, QueryPagination(limit, skip), where) =>
        groupingKeys should be(empty)
        limit should be(empty)
        skip should be(empty)
        where should be(empty)
        aggregationExpression should equal(Map("count(*)" -> CountStar()(pos)))

      case x =>
        fail(s"Expected AggregationProjection, got $x")
    }

    query.queryGraph.selections.predicates should be(empty)
    query.queryGraph.patternRelationships should be(empty)
    query.queryGraph.patternNodes should be(Set("n"))
  }

  test("MATCH (n) RETURN n.prop, count(*)") {
    val query = buildSinglePlannerQuery("MATCH (n) RETURN n.prop, count(*)")

    query.horizon match {
      case AggregatingQueryProjection(groupingKeys, aggregationExpression, QueryPagination(limit, skip), where) =>
        groupingKeys should equal(Map("n.prop" -> nProp))
        limit should be(empty)
        skip should be(empty)
        where should be(empty)
        aggregationExpression should equal(Map("count(*)" -> CountStar()(pos)))

      case x =>
        fail(s"Expected AggregationProjection, got $x")
    }

    query.queryGraph.selections.predicates should be(empty)
    query.queryGraph.patternRelationships should be(empty)
    query.queryGraph.patternNodes should be(Set("n"))
  }

  test("MATCH (n:Awesome {prop: 42}) USING INDEX n:Awesome(prop) RETURN n") {
    val query = buildSinglePlannerQuery("MATCH (n:Awesome {prop: 42}) USING INDEX n:Awesome(prop) RETURN n")

    query.queryGraph.hints should equal(Set[Hint](UsingIndexHint(
      varFor("n"),
      labelOrRelTypeName("Awesome"),
      Seq(PropertyKeyName("prop")(pos))
    ) _))
  }

  test("MATCH shortestPath((a)-[r]->(b)) RETURN r") {
    val query = buildSinglePlannerQuery("MATCH shortestPath((a)-[r]->(b)) RETURN r")

    query.queryGraph.patternNodes should equal(Set("a", "b"))
    query.queryGraph.shortestPathPatterns should equal(Set(
      ShortestPathPattern(
        Some("anon_0"),
        PatternRelationship("r", ("a", "b"), OUTGOING, Seq.empty, VarPatternLength(1, Some(1))),
        single = true
      )(null)
    ))
    query.tail should be(empty)
  }

  test("MATCH allShortestPaths((a)-[r]->(b)) RETURN r") {
    val query = buildSinglePlannerQuery("MATCH allShortestPaths((a)-[r]->(b)) RETURN r")

    query.queryGraph.patternNodes should equal(Set("a", "b"))
    query.queryGraph.shortestPathPatterns should equal(Set(
      ShortestPathPattern(
        Some("anon_0"),
        PatternRelationship("r", ("a", "b"), OUTGOING, Seq.empty, VarPatternLength(1, Some(1))),
        single = false
      )(null)
    ))
    query.tail should be(empty)
  }

  test("MATCH p = shortestPath((a)-[r]->(b)) RETURN p") {
    val query = buildSinglePlannerQuery("MATCH p = shortestPath((a)-[r]->(b)) RETURN p")

    query.queryGraph.patternNodes should equal(Set("a", "b"))
    query.queryGraph.shortestPathPatterns should equal(Set(
      ShortestPathPattern(
        Some("p"),
        PatternRelationship("r", ("a", "b"), OUTGOING, Seq.empty, VarPatternLength(1, Some(1))),
        single = true
      )(null)
    ))
    query.tail should be(empty)
  }

  test("match (n) return distinct n") {
    val query = buildSinglePlannerQuery("match (n) return distinct n")
    query.horizon should equal(DistinctQueryProjection(
      groupingExpressions = Map("n" -> varFor("n"))
    ))

    query.queryGraph.patternNodes should equal(Set("n"))
  }

  test("match (n) with distinct n.prop as x return x") {
    val query = buildSinglePlannerQuery("match (n) with distinct n.prop as x return x")
    query.horizon should equal(DistinctQueryProjection(
      groupingExpressions = Map("x" -> nProp)
    ))

    query.queryGraph.patternNodes should equal(Set("n"))
  }

  test("match (n) with distinct * return n") {
    val query = buildSinglePlannerQuery("match (n) with distinct * return n")
    query.horizon should equal(DistinctQueryProjection(
      groupingExpressions = Map("n" -> varFor("n"))
    ))

    query.queryGraph.patternNodes should equal(Set("n"))
  }

  test("WITH DISTINCT 1 as b RETURN b") {
    val query = buildSinglePlannerQuery("WITH DISTINCT 1 as b RETURN b")

    query.horizon should equal(DistinctQueryProjection(
      groupingExpressions = Map("b" -> literalInt(1))
    ))

    query.tail should not be empty
  }

  test("MATCH (owner) WITH owner, COUNT(*) AS collected WHERE (owner)--() RETURN owner") {
    val result =
      buildSinglePlannerQuery("MATCH (owner) WITH owner, COUNT(*) AS collected WHERE (owner)--() RETURN owner")

    val relName = "anon_0"
    val nodeName = "anon_1"
    val existsVariableName = "anon_4"

    // (owner)-[relName]-(nodeName)
    val subqueryExpression = ExistsIRExpression(
      PlannerQuery(
        RegularSinglePlannerQuery(
          QueryGraph(
            argumentIds = Set("owner"),
            patternNodes = Set("owner", nodeName),
            patternRelationships =
              Set(PatternRelationship(relName, ("owner", nodeName), BOTH, Seq(), SimplePatternLength))
          )
        )
      ),
      existsVariableName,
      s"EXISTS { MATCH (`owner`)-[`$relName`]-(`$nodeName`) }"
    )(pos)

    val expectation = RegularSinglePlannerQuery(
      queryGraph = QueryGraph(patternNodes = Set("owner")),
      horizon = AggregatingQueryProjection(
        groupingExpressions = Map("owner" -> varFor("owner")),
        aggregationExpressions = Map("collected" -> CountStar()(pos)),
        selections = Selections(Set(Predicate(Set("owner"), subqueryExpression)))
      ),
      tail = Some(RegularSinglePlannerQuery(
        queryGraph = QueryGraph(argumentIds = Set("collected", "owner")),
        horizon = RegularQueryProjection(projections = Map("owner" -> varFor("owner")))
      ))
    )

    result should equal(expectation)
  }

  test("UNWIND [1,2,3] AS x RETURN x") {
    val query = buildSinglePlannerQuery("UNWIND [1,2,3] AS x RETURN x")

    query.horizon should equal(UnwindProjection(
      "x",
      listOfInt(1, 2, 3)
    ))

    val tail = query.tail.get

    tail.horizon should equal(RegularQueryProjection(Map("x" -> varFor("x"))))
  }

  test("CALL foo() YIELD all RETURN all") {
    val signature = ProcedureSignature(
      QualifiedName(Seq.empty, "foo"),
      inputSignature = IndexedSeq.empty,
      deprecationInfo = None,
      outputSignature = Some(IndexedSeq(FieldSignature("all", CTInteger))),
      accessMode = ProcedureReadOnlyAccess,
      id = 42
    )
    val query = buildSinglePlannerQuery("CALL foo() YIELD all RETURN all", Some(_ => signature))

    query.horizon match {
      case ProcedureCallProjection(call) =>
        call.signature should equal(signature)
      case _ =>
        fail("Built wrong query horizon")
    }
  }

  test("WITH [1,2,3] as xes, 2 as y UNWIND xes AS x RETURN x, y") {
    val query = buildSinglePlannerQuery("WITH [1,2,3] as xes, 2 as y UNWIND xes AS x RETURN x, y")

    query.horizon should equal(RegularQueryProjection(
      projections = Map("xes" -> listOfInt(1, 2, 3), "y" -> literalInt(2))
    ))

    val tail = query.tail.get

    tail.horizon should equal(UnwindProjection(
      "x",
      varFor("xes")
    ))

    val tailOfTail = tail.tail.get
    val graph = tailOfTail.queryGraph

    graph.argumentIds should equal(Set("xes", "x", "y"))
  }

  test("UNWIND [1,2,3] AS x MATCH (n) WHERE n.prop = x RETURN n") {
    val query = buildSinglePlannerQuery("UNWIND [1,2,3] AS x MATCH (n) WHERE n.prop = x RETURN n")

    query.horizon should equal(UnwindProjection(
      "x",
      listOfInt(1, 2, 3)
    ))

    val tail = query.tail.get

    tail.queryGraph.patternNodes should equal(Set("n"))
    val set = Set(Predicate(Set("n", "x"), in(nProp, listOf(varFor("x")))))

    tail.queryGraph.selections.predicates should equal(set)
    tail.horizon should equal(RegularQueryProjection(Map("n" -> varFor("n"))))
  }

  test("MATCH (n) UNWIND n.prop as x RETURN x") {
    val query = buildSinglePlannerQuery("MATCH (n) UNWIND n.prop as x RETURN x")

    query.queryGraph.patternNodes should equal(Set("n"))

    query.horizon should equal(UnwindProjection(
      "x",
      nProp
    ))

    val tail = query.tail.get
    tail.queryGraph.patternNodes should equal(Set.empty)
    tail.horizon should equal(RegularQueryProjection(Map("x" -> varFor("x"))))
  }

  test("MATCH (row) WITH collect(row) AS rows UNWIND rows AS node RETURN node") {
    val query = buildSinglePlannerQuery("MATCH (row) WITH collect(row) AS rows UNWIND rows AS node RETURN node")

    query.queryGraph.patternNodes should equal(Set("row"))

    query.horizon should equal(
      AggregatingQueryProjection(
        groupingExpressions = Map.empty,
        aggregationExpressions = Map("rows" -> collect(varFor("row"))),
        queryPagination = QueryPagination.empty
      )
    )

    val tail = query.tail.get
    tail.queryGraph.patternNodes should equal(Set.empty)
    tail.horizon should equal(UnwindProjection(
      "node",
      varFor("rows")
    ))

    val tailOfTail = tail.tail.get
    tailOfTail.queryGraph.patternNodes should equal(Set.empty)
    tailOfTail.horizon should equal(
      RegularQueryProjection(
        projections = Map("node" -> varFor("node"))
      )
    )
  }

  test(
    "MATCH (a:A) OPTIONAL MATCH (a)-->(b:B) OPTIONAL MATCH (a)-->(c:C) WITH coalesce(b, c) as x MATCH (x)-->(d) RETURN d"
  ) {
    val query = buildSinglePlannerQuery(
      "MATCH (a:A) OPTIONAL MATCH (a)-->(b:B) OPTIONAL MATCH (a)-->(c:C) WITH coalesce(b, c) as x MATCH (x)-->(d) RETURN d"
    )

    query.queryGraph.patternNodes should equal(Set("a"))
    query.queryGraph.patternRelationships should equal(Set.empty)

    query.horizon should equal(RegularQueryProjection(
      projections = Map("x" -> function("coalesce", varFor("b"), varFor("c")))
    ))

    val optionalMatch1 = query.queryGraph.optionalMatches(0)
    val optionalMatch2 = query.queryGraph.optionalMatches(1)

    optionalMatch1.argumentIds should equal(Set("a"))
    optionalMatch1.patternNodes should equal(Set("a", "b"))

    optionalMatch2.argumentIds should equal(Set("a"))
    optionalMatch2.patternNodes should equal(Set("a", "c"))

    val tail = query.tail.get
    tail.queryGraph.argumentIds should equal(Set("x"))
    tail.queryGraph.patternNodes should equal(Set("x", "d"))

    tail.queryGraph.optionalMatches should be(empty)
  }

  test("OPTIONAL MATCH with dependency on shortest path") {
    val query = buildSinglePlannerQuery(
      "MATCH p=shortestPath( (a)-[r*]-(a0) ) OPTIONAL MATCH (a)--(b) WHERE b <> head(nodes(p)) RETURN p"
    )

    query.queryGraph.patternNodes should equal(Set("a", "a0"))
    query.queryGraph.patternRelationships should equal(Set.empty)

    val optionalMatch = query.queryGraph.optionalMatches(0)

    optionalMatch.argumentIds should equal(Set("a", "p"))
  }

  test("WITH-separated OPTIONAL MATCHes should be built into the same SinglePlannerQuery") {
    val query = buildSinglePlannerQuery(
      """MATCH (a)
        |OPTIONAL MATCH (a)-[r]-(b)
        |WITH a, b
        |OPTIONAL MATCH (a)-[s]-(c)
        |WITH *
        |OPTIONAL MATCH (c)-[t]-(d)
        |WITH a, b, c, d
        |MATCH (e) // This cannot be merged with the previous SingePlannerQuery any more
        |RETURN *
        |""".stripMargin
    )

    query should equal(RegularSinglePlannerQuery(
      QueryGraph(
        patternNodes = Set("a"),
        optionalMatches = IndexedSeq(
          QueryGraph(
            argumentIds = Set("a"),
            patternNodes = Set("a", "b"),
            patternRelationships = Set(PatternRelationship("r", ("a", "b"), BOTH, Seq.empty, SimplePatternLength))
          ),
          QueryGraph(
            argumentIds = Set("a"),
            patternNodes = Set("a", "c"),
            patternRelationships = Set(PatternRelationship("s", ("a", "c"), BOTH, Seq.empty, SimplePatternLength))
          ),
          QueryGraph(
            argumentIds = Set("c"),
            patternNodes = Set("c", "d"),
            patternRelationships = Set(PatternRelationship("t", ("c", "d"), BOTH, Seq.empty, SimplePatternLength))
          )
        )
      ),
      horizon =
        RegularQueryProjection(Map("a" -> varFor("a"), "b" -> varFor("b"), "c" -> varFor("c"), "d" -> varFor("d"))),
      tail = Some(
        RegularSinglePlannerQuery(
          QueryGraph(
            argumentIds = Set("a", "b", "c", "d"),
            patternNodes = Set("e")
          ),
          horizon = RegularQueryProjection(Map(
            "a" -> varFor("a"),
            "b" -> varFor("b"),
            "c" -> varFor("c"),
            "d" -> varFor("d"),
            "e" -> varFor("e")
          ))
        )
      )
    ))
  }

  test("WITH/WHERE-separated OPTIONAL MATCHes should not be built into the same SinglePlannerQuery") {
    val query = buildSinglePlannerQuery(
      """MATCH (a)
        |OPTIONAL MATCH (a)-[r]-(b)
        |WITH a, b WHERE a.prop > b.prop // WHERE clauses prevents the building into the same SinglePlannerQuery
        |OPTIONAL MATCH (a)-[s]-(c)
        |RETURN a, b, c
        |""".stripMargin
    )

    query should equal(RegularSinglePlannerQuery(
      QueryGraph(
        patternNodes = Set("a"),
        optionalMatches = IndexedSeq(
          QueryGraph(
            argumentIds = Set("a"),
            patternNodes = Set("a", "b"),
            patternRelationships = Set(PatternRelationship("r", ("a", "b"), BOTH, Seq.empty, SimplePatternLength))
          )
        )
      ),
      horizon = RegularQueryProjection(
        Map("a" -> varFor("a"), "b" -> varFor("b")),
        selections = Selections.from(greaterThan(prop("a", "prop"), prop("b", "prop")))
      ),
      tail = Some(
        RegularSinglePlannerQuery(
          QueryGraph(
            argumentIds = Set("a", "b"),
            optionalMatches = IndexedSeq(
              QueryGraph(
                argumentIds = Set("a"),
                patternNodes = Set("a", "c"),
                patternRelationships = Set(PatternRelationship("s", ("a", "c"), BOTH, Seq.empty, SimplePatternLength))
              )
            )
          ),
          horizon = RegularQueryProjection(Map("a" -> varFor("a"), "b" -> varFor("b"), "c" -> varFor("c")))
        )
      )
    ))
  }

  test("should inline relationship type predicate") {
    val query = buildSinglePlannerQuery(
      """MATCH (a)-[r]->(b)
        |WHERE r:REL
        |RETURN *
        |""".stripMargin
    )

    query.queryGraph.selections shouldBe Selections()
    query.queryGraph.patternRelationships shouldBe Set(
      PatternRelationship("r", ("a", "b"), SemanticDirection.OUTGOING, Seq(relTypeName("REL")), SimplePatternLength)
    )
  }

  test("should inline ORed relationship type predicates") {
    val query = buildSinglePlannerQuery(
      """MATCH (a)-[r]->(b)
        |WHERE r:X OR r:Y
        |RETURN *
        |""".stripMargin
    )

    query.queryGraph.selections shouldBe Selections()
    query.queryGraph.patternRelationships shouldBe Set(
      PatternRelationship(
        "r",
        ("a", "b"),
        SemanticDirection.OUTGOING,
        Seq(relTypeName("X"), relTypeName("Y")),
        SimplePatternLength
      )
    )
  }

  test("should inline relationship type predicates and keep other predicates") {
    val query = buildSinglePlannerQuery(
      """MATCH (a)-[r]->(b)
        |WHERE r:REL AND a.prop = 123
        |RETURN *
        |""".stripMargin
    )

    query.queryGraph.selections shouldBe Selections.from(in(prop("a", "prop"), listOfInt(123)))
    query.queryGraph.patternRelationships shouldBe Set(
      PatternRelationship("r", ("a", "b"), SemanticDirection.OUTGOING, Seq(relTypeName("REL")), SimplePatternLength)
    )
  }

  test("should insert an extra predicate when matching on the same standalone node") {
    val query = buildSinglePlannerQuery(
      """
        |MATCH (a)-[r1]->(b)
        |WITH *, 1 AS ignore
        |MATCH (a), (b)-[r2]->(c)
        |RETURN a, b, c
        |""".stripMargin
    )

    query.allPlannerQueries.map(q => q.queryGraph.patternNodes -> q.queryGraph.selections) shouldBe Seq(
      Set("a", "b") -> Selections(),
      Set("a", "b", "c") -> Selections.from(assertIsNode("a"))
    )
  }

  private def queryWith(qg: QueryGraph, horizon: QueryHorizon = RegularQueryProjection()): PlannerQuery = {
    PlannerQuery(
      RegularSinglePlannerQuery(
        queryGraph = qg,
        horizon = horizon,
        tail = None
      )
    )
  }

  test("should insert ExistsIRExpression in Selections when having an exists in a subquery") {
    val query = buildSinglePlannerQuery("MATCH (n)-[r]->(m) WHERE EXISTS { (o)-[r2]->(m)-[r3]->(q) } RETURN *")
    val m = varFor("m")
    val o = varFor("o")
    val q = varFor("q")
    val r2 = varFor("r2")
    val r3 = varFor("r3")

    val existsVariableName = "anon_0"

    query.queryGraph.selections shouldBe Selections(ListSet(Predicate(
      Set("m"),
      ExistsIRExpression(
        queryWith(
          QueryGraph(
            patternNodes = Set(m.name, o.name, q.name),
            patternRelationships =
              Set(
                PatternRelationship(varFor("r2").name, (o.name, m.name), OUTGOING, Seq.empty, SimplePatternLength),
                PatternRelationship(varFor("r3").name, (m.name, q.name), OUTGOING, Seq.empty, SimplePatternLength)
              ),
            argumentIds = Set("m"),
            selections = Selections.from(Seq(
              not(equals(r3, r2))
            ))
          )
        ),
        existsVariableName,
        "EXISTS { MATCH (o)-[r2]->(m)-[r3]->(q) }"
      )(pos)
    )))
  }

  // Note that namespaced names are removed in these tests by NameDeduplication
  test("should convert a single quantified pattern") {
    val query = buildSinglePlannerQuery("MATCH ((n)-[r]->(m))+ RETURN 1")
    query.queryGraph shouldBe QueryGraph(
      patternNodes = Set("anon_0", "anon_1"),
      quantifiedPathPatterns = Set(
        QuantifiedPathPattern(
          leftBinding = NodeBinding("n", "anon_0"),
          rightBinding = NodeBinding("m", "anon_1"),
          pattern = QueryGraph(
            patternNodes = Set("n", "m"),
            patternRelationships =
              Set(PatternRelationship(
                "r",
                ("n", "m"),
                SemanticDirection.OUTGOING,
                Seq.empty,
                SimplePatternLength
              ))
          ),
          repetition = Repetition(min = 1, max = UpperBound.Unlimited),
          nodeVariableGroupings = Set(VariableGrouping("n", "n"), VariableGrouping("m", "m")),
          relationshipVariableGroupings = Set(VariableGrouping("r", "r"))
        )
      ),
      selections = Selections.from(unique(varFor("r")))
    )
  }

  test("should convert a single quantified pattern with no node or relationship bindings") {
    val query = buildSinglePlannerQuery("MATCH (()-->())+ RETURN 1")
    query.queryGraph shouldBe QueryGraph(
      patternNodes = Set("anon_0", "anon_4"),
      quantifiedPathPatterns = Set(
        QuantifiedPathPattern(
          leftBinding = NodeBinding("anon_1", "anon_0"),
          rightBinding = NodeBinding("anon_3", "anon_4"),
          pattern = QueryGraph(
            patternNodes = Set("anon_1", "anon_3"),
            patternRelationships =
              Set(PatternRelationship(
                "anon_5",
                ("anon_1", "anon_3"),
                SemanticDirection.OUTGOING,
                Seq.empty,
                SimplePatternLength
              ))
          ),
          repetition = Repetition(min = 1, max = UpperBound.Unlimited),
          nodeVariableGroupings = Set.empty,
          relationshipVariableGroupings = Set(VariableGrouping("anon_5", "anon_6"))
        )
      ),
      selections = Selections.from(unique(varFor("anon_6")))
    )
  }

  test("should convert a single quantified pattern with explicitly juxtaposed nodes") {
    val query = buildSinglePlannerQuery("MATCH (a) ((n)-[r]->(m)){2, 5} (b) RETURN 1")
    query.queryGraph shouldBe QueryGraph(
      patternNodes = Set("a", "b"),
      quantifiedPathPatterns = Set(
        QuantifiedPathPattern(
          leftBinding = NodeBinding("n", "a"),
          rightBinding = NodeBinding("m", "b"),
          pattern = QueryGraph(
            patternNodes = Set("n", "m"),
            patternRelationships =
              Set(PatternRelationship(
                "r",
                ("n", "m"),
                SemanticDirection.OUTGOING,
                Seq.empty,
                SimplePatternLength
              ))
          ),
          repetition = Repetition(min = 2, max = UpperBound.Limited(5)),
          nodeVariableGroupings = Set(VariableGrouping("n", "n"), VariableGrouping("m", "m")),
          relationshipVariableGroupings = Set(VariableGrouping("r", "r"))
        )
      ),
      selections = Selections.from(unique(varFor("r")))
    )
  }

  test("should convert a single quantified pattern with explicitly juxtaposed patterns") {
    val query = buildSinglePlannerQuery("MATCH (a)-[r1]->(b) ((n)-[r2]->(m)){3,} (x)-[r3]->(y) RETURN 1")
    query.queryGraph shouldBe QueryGraph(
      patternNodes = Set("a", "b", "x", "y"),
      patternRelationships = Set(
        PatternRelationship("r1", ("a", "b"), SemanticDirection.OUTGOING, Seq.empty, SimplePatternLength),
        PatternRelationship("r3", ("x", "y"), SemanticDirection.OUTGOING, Seq.empty, SimplePatternLength)
      ),
      selections = Selections.from(Set(
        not(equals(varFor("r1"), varFor("r3"))),
        unique(varFor("r2")),
        not(in(varFor("r1"), varFor("r2"))),
        not(in(varFor("r3"), varFor("r2")))
      )),
      quantifiedPathPatterns = Set(
        QuantifiedPathPattern(
          leftBinding = NodeBinding("n", "b"),
          rightBinding = NodeBinding("m", "x"),
          pattern = QueryGraph(
            patternNodes = Set("n", "m"),
            patternRelationships =
              Set(PatternRelationship(
                "r2",
                ("n", "m"),
                SemanticDirection.OUTGOING,
                Seq.empty,
                SimplePatternLength
              ))
          ),
          repetition = Repetition(min = 3, max = UpperBound.Unlimited),
          nodeVariableGroupings = Set(VariableGrouping("n", "n"), VariableGrouping("m", "m")),
          relationshipVariableGroupings = Set(VariableGrouping("r2", "r2"))
        )
      )
    )
  }

  test("should convert a single quantified pattern with explicitly juxtaposed patterns with relationship types") {
    val query = buildSinglePlannerQuery("MATCH (a)-[r1:R1]->(b) ((n)-[r2:R2]->(m)){3,} (x)-[r3:R3]->(y) RETURN 1")
    query.queryGraph shouldBe QueryGraph(
      patternNodes = Set("a", "b", "x", "y"),
      patternRelationships = Set(
        PatternRelationship("r1", ("a", "b"), SemanticDirection.OUTGOING, Seq(relTypeName("R1")), SimplePatternLength),
        PatternRelationship("r3", ("x", "y"), SemanticDirection.OUTGOING, Seq(relTypeName("R3")), SimplePatternLength)
      ),
      quantifiedPathPatterns = Set(
        QuantifiedPathPattern(
          leftBinding = NodeBinding("n", "b"),
          rightBinding = NodeBinding("m", "x"),
          pattern = QueryGraph(
            patternNodes = Set("n", "m"),
            patternRelationships =
              Set(PatternRelationship(
                "r2",
                ("n", "m"),
                SemanticDirection.OUTGOING,
                Seq(relTypeName("R2")),
                SimplePatternLength
              ))
          ),
          repetition = Repetition(min = 3, max = UpperBound.Unlimited),
          nodeVariableGroupings = Set(VariableGrouping("n", "n"), VariableGrouping("m", "m")),
          relationshipVariableGroupings = Set(VariableGrouping("r2", "r2"))
        )
      ),
      selections = Selections.from(unique(varFor("r2")))
    )
  }

  test("should convert consecutive quantified patterns") {
    val query = buildSinglePlannerQuery("MATCH ((n)-[r1]->(m))+ ((x)-[r2]->(y)){,3} RETURN 1")
    query.queryGraph shouldBe QueryGraph(
      patternNodes = Set("anon_0", "anon_1", "anon_2"),
      selections = Selections.from(Seq(
        unique(varFor("r1")),
        unique(varFor("r2")),
        disjoint(varFor("r1"), varFor("r2"))
      )),
      quantifiedPathPatterns = Set(
        QuantifiedPathPattern(
          leftBinding = NodeBinding("n", "anon_0"),
          rightBinding = NodeBinding("m", "anon_1"),
          pattern = QueryGraph(
            patternNodes = Set("n", "m"),
            patternRelationships =
              Set(PatternRelationship(
                "r1",
                ("n", "m"),
                SemanticDirection.OUTGOING,
                Seq.empty,
                SimplePatternLength
              ))
          ),
          repetition = Repetition(min = 1, max = UpperBound.Unlimited),
          nodeVariableGroupings = Set(VariableGrouping("n", "n"), VariableGrouping("m", "m")),
          relationshipVariableGroupings = Set(VariableGrouping("r1", "r1"))
        ),
        QuantifiedPathPattern(
          leftBinding = NodeBinding("x", "anon_1"),
          rightBinding = NodeBinding("y", "anon_2"),
          pattern = QueryGraph(
            patternNodes = Set("x", "y"),
            patternRelationships =
              Set(PatternRelationship(
                "r2",
                ("x", "y"),
                SemanticDirection.OUTGOING,
                Seq.empty,
                SimplePatternLength
              ))
          ),
          repetition = Repetition(min = 0, max = UpperBound.Limited(3)),
          nodeVariableGroupings = Set(VariableGrouping("x", "x"), VariableGrouping("y", "y")),
          relationshipVariableGroupings = Set(VariableGrouping("r2", "r2"))
        )
      )
    )
  }

  test("should convert a larger consecutive quantified pattern") {
    val query = buildSinglePlannerQuery("MATCH ((a)-[r1]->(b)-[r2]->(c)){5} ((x)-[r3]->(y))+ RETURN 1")
    query.queryGraph shouldBe QueryGraph(
      patternNodes = Set("anon_0", "anon_1", "anon_2"),
      selections = Selections.from(Seq(
        disjoint(add(varFor("r1"), varFor("r2")), varFor("r3")),
        unique(add(varFor("r1"), varFor("r2"))),
        unique(varFor("r3"))
      )),
      quantifiedPathPatterns = Set(
        QuantifiedPathPattern(
          leftBinding = NodeBinding("a", "anon_0"),
          rightBinding = NodeBinding("c", "anon_1"),
          pattern = QueryGraph(
            patternNodes = Set("a", "b", "c"),
            patternRelationships =
              Set(
                PatternRelationship(
                  "r1",
                  ("a", "b"),
                  SemanticDirection.OUTGOING,
                  Seq.empty,
                  SimplePatternLength
                ),
                PatternRelationship(
                  "r2",
                  ("b", "c"),
                  SemanticDirection.OUTGOING,
                  Seq.empty,
                  SimplePatternLength
                )
              ),
            selections = Selections.from(not(equals(varFor("r2"), varFor("r1"))))
          ),
          repetition = Repetition(min = 5, max = UpperBound.Limited(5)),
          nodeVariableGroupings =
            Set(VariableGrouping("a", "a"), VariableGrouping("b", "b"), VariableGrouping("c", "c")),
          relationshipVariableGroupings = Set(VariableGrouping("r1", "r1"), VariableGrouping("r2", "r2"))
        ),
        QuantifiedPathPattern(
          leftBinding = NodeBinding("x", "anon_1"),
          rightBinding = NodeBinding("y", "anon_2"),
          pattern = QueryGraph(
            patternNodes = Set("x", "y"),
            patternRelationships =
              Set(PatternRelationship(
                "r3",
                ("x", "y"),
                SemanticDirection.OUTGOING,
                Seq.empty,
                SimplePatternLength
              ))
          ),
          repetition = Repetition(min = 1, max = UpperBound.Unlimited),
          nodeVariableGroupings = Set(VariableGrouping("x", "x"), VariableGrouping("y", "y")),
          relationshipVariableGroupings = Set(VariableGrouping("r3", "r3"))
        )
      )
    )
  }

  test("should convert a single quantified pattern in OPTIONAL MATCH") {
    val query = buildSinglePlannerQuery("OPTIONAL MATCH ((n)-[r]->(m))+ RETURN 1")
    query.queryGraph shouldBe QueryGraph(
      optionalMatches = Vector(QueryGraph(
        patternNodes = Set("anon_0", "anon_1"),
        quantifiedPathPatterns = Set(
          QuantifiedPathPattern(
            leftBinding = NodeBinding("n", "anon_0"),
            rightBinding = NodeBinding("m", "anon_1"),
            pattern = QueryGraph(
              patternNodes = Set("n", "m"),
              patternRelationships =
                Set(PatternRelationship(
                  "r",
                  ("n", "m"),
                  SemanticDirection.OUTGOING,
                  Seq.empty,
                  SimplePatternLength
                ))
            ),
            repetition = Repetition(min = 1, max = UpperBound.Unlimited),
            nodeVariableGroupings = Set(VariableGrouping("n", "n"), VariableGrouping("m", "m")),
            relationshipVariableGroupings = Set(VariableGrouping("r", "r"))
          )
        ),
        selections = Selections.from(unique(varFor("r")))
      ))
    )
  }

  test("should convert quantified pattern inside EXISTS subquery") {
    val query = buildSinglePlannerQuery("MATCH (a) WHERE EXISTS { (a) ((n)-[r]->(m))+ } RETURN 1")

    val m_outer = "anon_0"
    val existsVariableName = "anon_7"
    val n = "n"
    val r = "r"
    val m = "m"

    val qpp = QuantifiedPathPattern(
      leftBinding = NodeBinding(n, "a"),
      rightBinding = NodeBinding(m, m_outer),
      pattern = QueryGraph(
        patternNodes = Set(n, m),
        patternRelationships =
          Set(PatternRelationship(
            r,
            (n, m),
            SemanticDirection.OUTGOING,
            Seq.empty,
            SimplePatternLength
          ))
      ),
      repetition = Repetition(min = 1, max = UpperBound.Unlimited),
      nodeVariableGroupings = Set(VariableGrouping(n, n), VariableGrouping(m, m)),
      relationshipVariableGroupings = Set(VariableGrouping(r, r))
    )

    query.queryGraph.selections shouldBe Selections(ListSet(Predicate(
      dependencies = Set("a"),
      expr = ExistsIRExpression(
        queryWith(
          QueryGraph(
            argumentIds = Set("a"),
            patternNodes = Set("a", m_outer),
            quantifiedPathPatterns = Set(qpp),
            selections = Selections.from(unique(varFor("r")))
          )
        ),
        existsVariableName,
        s"EXISTS { MATCH (a) ((`n`)-[`r`]->(`m`))+ (`$m_outer`) }"
      )(pos)
    )))
  }

  test("should convert a quantified pattern with a where clause") {
    val query = buildSinglePlannerQuery("MATCH ((n)-[r]->(m) WHERE n.prop > m.prop)+ RETURN 1")
    query.queryGraph shouldBe QueryGraph(
      patternNodes = Set("anon_0", "anon_1"),
      quantifiedPathPatterns = Set(
        QuantifiedPathPattern(
          leftBinding = NodeBinding("n", "anon_0"),
          rightBinding = NodeBinding("m", "anon_1"),
          pattern = QueryGraph(
            patternNodes = Set("n", "m"),
            patternRelationships =
              Set(PatternRelationship(
                "r",
                ("n", "m"),
                SemanticDirection.OUTGOING,
                Seq.empty,
                SimplePatternLength
              )),
            selections = Selections.from(greaterThan(prop("n", "prop"), prop("m", "prop")))
          ),
          repetition = Repetition(min = 1, max = UpperBound.Unlimited),
          nodeVariableGroupings = Set(VariableGrouping("n", "n"), VariableGrouping("m", "m")),
          relationshipVariableGroupings = Set(VariableGrouping("r", "r"))
        )
      ),
      selections = Selections.from(unique(varFor("r")))
    )
  }

}

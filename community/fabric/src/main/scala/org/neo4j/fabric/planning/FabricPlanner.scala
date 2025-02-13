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
package org.neo4j.fabric.planning

import org.neo4j.cypher.internal.FullyParsedQuery
import org.neo4j.cypher.internal.PreParsedQuery
import org.neo4j.cypher.internal.QueryOptions
import org.neo4j.cypher.internal.ast.CatalogName
import org.neo4j.cypher.internal.cache.CaffeineCacheFactory
import org.neo4j.cypher.internal.config.CypherConfiguration
import org.neo4j.cypher.internal.options.CypherExpressionEngineOption
import org.neo4j.cypher.internal.options.CypherRuntimeOption
import org.neo4j.cypher.internal.planner.spi.ProcedureSignatureResolver
import org.neo4j.cypher.internal.util.CancellationChecker
import org.neo4j.cypher.rendering.QueryRenderer
import org.neo4j.fabric.cache.FabricQueryCache
import org.neo4j.fabric.config.FabricConfig
import org.neo4j.fabric.eval.Catalog
import org.neo4j.fabric.eval.UseEvaluation
import org.neo4j.fabric.pipeline.FabricFrontEnd
import org.neo4j.fabric.planning.FabricPlan.DebugOptions
import org.neo4j.fabric.planning.FabricQuery.LocalQuery
import org.neo4j.fabric.planning.FabricQuery.RemoteQuery
import org.neo4j.monitoring.Monitors
import org.neo4j.values.virtual.MapValue

case class FabricPlanner(
  config: FabricConfig,
  cypherConfig: CypherConfiguration,
  monitors: Monitors,
  cacheFactory: CaffeineCacheFactory,
  signatures: ProcedureSignatureResolver
) {

  private[planning] val queryCache = new FabricQueryCache(cacheFactory, cypherConfig.queryCacheSize)

  private val frontend = FabricFrontEnd(cypherConfig, monitors, signatures, cacheFactory)

  private def fabricContextName: Option[String] = {
    None
  }

  /**
   * Convenience method without cancellation checker. Should be used for tests only.
   */
  def instance(
    queryString: String,
    queryParams: MapValue,
    defaultGraphName: String,
    catalog: Catalog
  ): PlannerInstance =
    instance(queryString, queryParams, defaultGraphName, catalog, CancellationChecker.NeverCancelled)

  def instance(
    queryString: String,
    queryParams: MapValue,
    defaultGraphName: String,
    catalog: Catalog,
    cancellationChecker: CancellationChecker
  ): PlannerInstance = {
    val query = frontend.preParsing.preParse(queryString)
    PlannerInstance(query, queryParams, defaultGraphName, fabricContextName, catalog, cancellationChecker)
  }

  case class PlannerInstance(
    query: PreParsedQuery,
    queryParams: MapValue,
    defaultContextName: String,
    fabricContextName: Option[String],
    catalog: Catalog,
    cancellationChecker: CancellationChecker
  ) {

    private lazy val pipeline = frontend.Pipeline(query, queryParams, cancellationChecker)

    private val useHelper = new UseHelper(catalog, defaultContextName, fabricContextName)

    lazy val plan: FabricPlan = {
      val plan = queryCache.computeIfAbsent(
        query.cacheKey,
        queryParams,
        defaultContextName,
        () => computePlan(),
        shouldCache
      )
      plan.copy(
        executionType = frontend.preParsing.executionType(query.options, plan.inFabricContext)
      )
    }

    private def computePlan(): FabricPlan = trace {
      val prepared = pipeline.parseAndPrepare.process()

      val fragmenter =
        new FabricFragmenter(defaultContextName, query.statement, prepared.statement(), prepared.semantics())
      val fragments = fragmenter.fragment

      val fabricContext = useHelper.rootTargetsCompositeContext(fragments)

      val stitcher = FabricStitcher(query.statement, fabricContext, fabricContextName, pipeline, useHelper)
      val stitchedFragments = stitcher.convert(fragments)

      FabricPlan(
        query = stitchedFragments,
        queryType = QueryType.recursive(stitchedFragments),
        executionType = FabricPlan.Execute,
        queryString = query.statement,
        debugOptions = DebugOptions.from(query.options.queryOptions.debugOptions),
        obfuscationMetadata = prepared.obfuscationMetadata(),
        inFabricContext = fabricContext,
        notifications = pipeline.notifications
      )
    }

    private def shouldCache(plan: FabricPlan): Boolean =
      !QueryType.sensitive(plan.query)

    private def optionsFor(fragment: Fragment) =
      if (useHelper.fragmentTargetsCompositeContext(fragment))
        QueryOptions.default.copy(
          queryOptions = QueryOptions.default.queryOptions.copy(
            runtime = CypherRuntimeOption.slotted,
            expressionEngine = CypherExpressionEngineOption.interpreted
          ),
          materializedEntitiesMode = true
        )
      else
        query.options

    private def trace(compute: => FabricPlan): FabricPlan = {
      val event = pipeline.traceStart()
      try compute
      finally event.close()
    }

    def asLocal(fragment: Fragment.Exec): LocalQuery = LocalQuery(
      FullyParsedQuery(fragment.localQuery, optionsFor(fragment)),
      fragment.queryType
    )

    def asRemote(fragment: Fragment.Exec): RemoteQuery = RemoteQuery(
      QueryRenderer.addOptions(fragment.remoteQuery.query, optionsFor(fragment)),
      fragment.queryType,
      fragment.remoteQuery.extractedLiterals
    )

    def targetsComposite(fragment: Fragment.Exec): Boolean =
      useHelper.fragmentTargetsCompositeContext(fragment)

    private[planning] def withForceFabricContext(force: Boolean) =
      if (force) this.copy(fabricContextName = Some(defaultContextName))
      else this.copy(fabricContextName = None)
  }
}

class UseHelper(catalog: Catalog, defaultContextName: String, fabricContextName: Option[String]) {

  def rootTargetsCompositeContext(fragment: Fragment): Boolean = {
    def inFabricDefaultContext =
      fabricContextName.contains(defaultContextName)

    def inCompositeDefaultContext =
      isComposite(CatalogName(defaultContextName))

    inFabricDefaultContext || inCompositeDefaultContext || fragmentTargetsCompositeContext(fragment)
  }

  def fragmentTargetsCompositeContext(fragment: Fragment): Boolean = {
    def check(frag: Fragment): Boolean = frag match {
      case chain: Fragment.Chain     => useTargetsCompositeContext(chain.use)
      case union: Fragment.Union     => check(union.lhs) && check(union.rhs)
      case command: Fragment.Command => useTargetsCompositeContext(command.use)
    }

    check(fragment)
  }

  def useTargetsCompositeContext(use: Use): Boolean = {
    UseEvaluation.evaluateStatic(use.graphSelection).exists(name => {
      val isFabric = name.parts == fabricContextName.toList
      isFabric || isComposite(name)
    })
  }

  private def isComposite(name: CatalogName): Boolean =
    catalog.resolveGraphOption(name) match {
      case Some(_: Catalog.Composite) => true
      case _                          => false
    }
}

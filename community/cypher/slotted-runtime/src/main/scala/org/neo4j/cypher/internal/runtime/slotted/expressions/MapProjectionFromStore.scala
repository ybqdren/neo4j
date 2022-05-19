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
package org.neo4j.cypher.internal.runtime.slotted.expressions

import org.neo4j.cypher.internal.physicalplanning.ast.PropertyFromStore
import org.neo4j.cypher.internal.runtime.PropertyTokensResolver
import org.neo4j.cypher.internal.runtime.ReadableRow
import org.neo4j.cypher.internal.runtime.interpreted.commands.AstNode
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.Expression
import org.neo4j.cypher.internal.runtime.interpreted.pipes.QueryState
import org.neo4j.cypher.operations.CursorUtils.entityGetProperties
import org.neo4j.internal.kernel.api.EntityCursor
import org.neo4j.internal.kernel.api.TokenRead
import org.neo4j.kernel.api.StatementConstants
import org.neo4j.values.AnyValue
import org.neo4j.values.storable.Values
import org.neo4j.values.virtual.MapValueBuilder

abstract class MapProjectionFromStore extends Expression with SlottedExpression {

  final private[this] val tokens: PropertyTokensResolver = {
    val (names, tokens) = properties
      .sortBy(_.name)
      .map(p => p.name -> p.token.getOrElse(TokenRead.NO_TOKEN))
      .unzip
    PropertyTokensResolver.property(names.toArray, tokens.toArray)
  }

  override def children: Seq[AstNode[_]] = Seq.empty

  override def apply(row: ReadableRow, state: QueryState): AnyValue = {
    tokens.populate(state.query)

    val id = row.getLongAt(entityOffset)
    if (id == StatementConstants.NO_SUCH_ENTITY) {
      Values.NO_VALUE
    } else {
      val cursor = entityCursor(id, state)
      val values = entityGetProperties(cursor, state.cursors.propertyCursor, tokens.tokens())
      val result = new MapValueBuilder(values.length)
      values.indices.foreach(i => result.add(tokens.names()(i), values(i)))
      result.build()
    }
  }

  protected def entityOffset: Int
  protected def properties: Seq[PropertyFromStore]
  protected def entityCursor(id: Long, state: QueryState): EntityCursor
}

case class NodeProjectionFromStore(entityOffset: Int, properties: Seq[PropertyFromStore])
    extends MapProjectionFromStore {

  override protected def entityCursor(id: Long, state: QueryState): EntityCursor = {
    val cursor = state.cursors.nodeCursor
    state.query.singleNodePositioned(id, cursor)
    cursor
  }
}

case class RelationshipProjectionFromStore(entityOffset: Int, properties: Seq[PropertyFromStore])
    extends MapProjectionFromStore {

  override protected def entityCursor(id: Long, state: QueryState): EntityCursor = {
    val cursor = state.cursors.relationshipScanCursor
    state.query.singleRelationshipPositioned(id, cursor)
    cursor
  }
}

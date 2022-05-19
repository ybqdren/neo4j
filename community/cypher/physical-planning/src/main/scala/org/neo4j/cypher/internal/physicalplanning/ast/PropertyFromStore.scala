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
package org.neo4j.cypher.internal.physicalplanning.ast

import org.neo4j.cypher.internal.runtime.ast.RuntimeProperty

/**
 * Helper trait to identify properties that needs to be read from store.
 * 
 * The implementation needs to null check the entity!
 */
sealed trait PropertyFromStore {
  def offset: Int
  def name: String
  def token: Option[Int]
}

case class NodePropertyFromStore(offset: Int, name: String, token: Option[Int]) extends PropertyFromStore
case class RelationshipPropertyFromStore(offset: Int, name: String, token: Option[Int]) extends PropertyFromStore

object PropertyFromStore {

  def unapply(property: RuntimeProperty): Option[PropertyFromStore] = property match {
    case p @ NodeProperty(offset, token, _)   => Some(NodePropertyFromStore(offset, p.propertyKey.name, Some(token)))
    case NodePropertyLate(offset, propKey, _) => Some(NodePropertyFromStore(offset, propKey, None))
    case p @ RelationshipProperty(offset, token, _) =>
      Some(RelationshipPropertyFromStore(offset, p.propertyKey.name, Some(token)))
    case RelationshipPropertyLate(offset, propKey, _) => Some(RelationshipPropertyFromStore(offset, propKey, None))
    // Null checks are erased here, meaning that uses of PropertyFromStore needs null checking
    case NullCheckProperty(_, PropertyFromStore(p)) => Some(p)
    case _                                          => None
  }
}

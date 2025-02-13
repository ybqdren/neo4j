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
package org.neo4j.kernel.impl.coreapi.schema;

import static java.lang.String.format;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.schema.ConstraintType;
import org.neo4j.internal.schema.ConstraintDescriptor;

public class NodePropertyExistenceConstraintDefinition extends NodeConstraintDefinition {
    public NodePropertyExistenceConstraintDefinition(
            InternalSchemaActions actions, ConstraintDescriptor constraint, Label label, String[] propertyKeys) {
        super(actions, constraint, label, propertyKeys);
    }

    @Override
    public ConstraintType getConstraintType() {
        assertInUnterminatedTransaction();
        return ConstraintType.NODE_PROPERTY_EXISTENCE;
    }

    @Override
    public String toString() {
        return format(
                "FOR (%1$s:%2$s) REQUIRE %3$s IS NOT NULL", label.name().toLowerCase(), label.name(), propertyText());
    }
}

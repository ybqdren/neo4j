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
package org.neo4j.kernel.impl.locking;

import org.neo4j.annotations.service.Service;
import org.neo4j.configuration.Config;
import org.neo4j.service.NamedService;
import org.neo4j.service.PrioritizedService;
import org.neo4j.time.SystemNanoClock;

@Service
public interface LocksFactory extends NamedService, PrioritizedService {
    Locks newInstance(Config config, SystemNanoClock clock);
}

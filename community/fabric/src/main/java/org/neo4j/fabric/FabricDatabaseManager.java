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
package org.neo4j.fabric;

import org.neo4j.configuration.Config;
import org.neo4j.dbms.api.DatabaseNotFoundException;
import org.neo4j.dbms.database.DatabaseContext;
import org.neo4j.dbms.database.DatabaseContextProvider;
import org.neo4j.fabric.config.FabricConfig;
import org.neo4j.fabric.config.FabricSettings;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.kernel.availability.UnavailableException;
import org.neo4j.kernel.database.DatabaseIdRepository;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade;

public abstract class FabricDatabaseManager {
    private final DatabaseContextProvider<? extends DatabaseContext> databaseContextProvider;
    private final DatabaseIdRepository databaseIdRepository;
    private final boolean multiGraphEverywhere;

    public FabricDatabaseManager(
            FabricConfig fabricConfig, DatabaseContextProvider<? extends DatabaseContext> databaseContextProvider) {
        this.databaseContextProvider = databaseContextProvider;
        this.databaseIdRepository = databaseContextProvider.databaseIdRepository();
        this.multiGraphEverywhere = fabricConfig.isEnabledByDefault();
    }

    public static boolean fabricByDefault(Config config) {
        return config.get(FabricSettings.enabled_by_default);
    }

    public DatabaseIdRepository databaseIdRepository() {
        return databaseIdRepository;
    }

    public boolean hasMultiGraphCapabilities(String databaseNameRaw) {
        return multiGraphCapabilitiesEnabledForAllDatabases() || isFabricDatabase(databaseNameRaw);
    }

    public boolean multiGraphCapabilitiesEnabledForAllDatabases() {
        return multiGraphEverywhere;
    }

    public GraphDatabaseFacade getDatabase(String databaseNameRaw) throws UnavailableException {
        var databaseContext = databaseIdRepository
                .getByName(databaseNameRaw)
                .flatMap(databaseContextProvider::getDatabaseContext)
                .orElseThrow(() -> new DatabaseNotFoundException("Database " + databaseNameRaw + " not found"));

        databaseContext.database().getDatabaseAvailabilityGuard().assertDatabaseAvailable();
        return databaseContext.databaseFacade();
    }

    public abstract boolean isFabricDatabasePresent();

    public abstract void manageFabricDatabases(GraphDatabaseService system);

    public abstract boolean isFabricDatabase(String databaseNameRaw);

    public static class Community extends FabricDatabaseManager {
        public Community(FabricConfig fabricConfig, DatabaseContextProvider<? extends DatabaseContext> databaseContextProvider) {
            super(fabricConfig, databaseContextProvider);
        }

        @Override
        public boolean isFabricDatabasePresent() {
            return false;
        }

        @Override
        public void manageFabricDatabases(GraphDatabaseService system) {
            // a "Fabric" database with special capabilities cannot exist in CE
        }

        @Override
        public boolean isFabricDatabase(String databaseNameRaw) {
            // a "Fabric" database with special capabilities cannot exist in CE
            return false;
        }
    }
}

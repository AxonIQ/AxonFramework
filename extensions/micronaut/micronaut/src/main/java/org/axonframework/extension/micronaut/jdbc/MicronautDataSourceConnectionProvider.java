/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.extension.micronaut.jdbc;

import org.axonframework.common.jdbc.ConnectionProvider;

import java.sql.Connection;
import java.sql.SQLException;
import jakarta.annotation.Nonnull;
import javax.sql.DataSource;

/**
 * TODO: redo
 * ConnectionProvider implementation that is aware of Transaction Managers and provides the connection attached to an
 * active transaction manager, instead of asking a Data Source directly.
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
public class MicronautDataSourceConnectionProvider implements ConnectionProvider {

    private final DataSource dataSource;

    /**
     * Initialize the connection provider, using given {@code dataSource} to obtain a connection, when required.
     *
     * @param dataSource The data source to obtain connections from, when required
     */
    public MicronautDataSourceConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Nonnull
    @Override
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
}

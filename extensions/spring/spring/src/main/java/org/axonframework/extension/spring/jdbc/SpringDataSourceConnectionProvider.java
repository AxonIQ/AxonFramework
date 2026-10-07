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

package org.axonframework.extension.spring.jdbc;

import org.axonframework.common.jdbc.ConnectionProvider;
import org.axonframework.common.jdbc.ConnectionWrapperFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * ConnectionProvider implementation that is aware of Transaction Managers and provides the connection attached to an
 * active transaction manager, instead of asking a Data Source directly.
 *
 * @author Allard Buijze
 * @since 2.2
 */
public class SpringDataSourceConnectionProvider implements ConnectionProvider {

    private final DataSource dataSource;
    private final SpringConnectionCloseHandler closeHandler;

    /**
     * Initialize the connection provider, using given {@code dataSource} to obtain a connection, when required.
     *
     * @param dataSource The data source to obtain connections from, when required
     */
    public SpringDataSourceConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
        this.closeHandler = new SpringConnectionCloseHandler(dataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        final Connection connection = DataSourceUtils.doGetConnection(dataSource);
        return ConnectionWrapperFactory.wrap(connection, closeHandler);
    }

    /**
     * Returns the {@link DataSource} this provider obtains its connections from.
     *
     * @return The {@link DataSource} this provider obtains its connections from.
     */
    public DataSource dataSource() {
        return dataSource;
    }

    private static class SpringConnectionCloseHandler implements ConnectionWrapperFactory.ConnectionCloseHandler {

        private final DataSource dataSource;

        public SpringConnectionCloseHandler(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public void close(Connection delegate) {
            DataSourceUtils.releaseConnection(delegate, dataSource);
        }

        @Override
        public void commit(Connection connection) throws SQLException {
            if (!DataSourceUtils.isConnectionTransactional(connection, dataSource)) {
                connection.commit();
            }
        }
    }
}

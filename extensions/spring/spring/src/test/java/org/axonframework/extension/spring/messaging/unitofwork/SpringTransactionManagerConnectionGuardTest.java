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

package org.axonframework.extension.spring.messaging.unitofwork;

import org.axonframework.common.jdbc.ConnectionProvider;
import org.axonframework.extension.spring.jdbc.SpringDataSourceConnectionProvider;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.transaction.jdbc.JdbcTransactionalExecutorProvider;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.common.FutureUtils.joinAndUnwrap;

/**
 * Tests that the {@link SpringTransactionManager} only hands out connections that take part in its transaction.
 *
 * @author John Hendrikx
 */
class SpringTransactionManagerConnectionGuardTest {

    @Test
    void shouldProvideTheConnectionOfTheTransaction() {
        // given
        DataSource dataSource = dataSource(uniqueDbName());
        UnitOfWorkFactory unitOfWorkFactory =
                unitOfWorkFactory(dataSource, new SpringDataSourceConnectionProvider(dataSource));

        // when
        Boolean autoCommit = joinAndUnwrap(unitOfWorkFactory.create().executeWithResult(
                context -> context.getResource(JdbcTransactionalExecutorProvider.SUPPLIER_KEY)
                                  .get()
                                  .apply(Connection::getAutoCommit)));

        // then
        assertThat(autoCommit).isFalse();
    }

    @Test
    void shouldRefuseAndCloseAConnectionThatIsNotPartOfTheTransaction() throws Exception {
        // given
        String db = uniqueDbName();
        DataSource transactionDataSource = dataSource(db);
        DataSource otherDataSource = dataSource(db);
        List<Connection> provided = new ArrayList<>();
        ConnectionProvider provider = () -> {
            Connection connection = otherDataSource.getConnection();

            provided.add(connection);

            return connection;
        };
        UnitOfWorkFactory unitOfWorkFactory = unitOfWorkFactory(transactionDataSource, provider);

        // when
        Runnable useConnection = () -> joinAndUnwrap(unitOfWorkFactory.create().executeWithResult(
                context -> context.getResource(JdbcTransactionalExecutorProvider.SUPPLIER_KEY)
                                  .get()
                                  .apply(connection -> connection)));

        // then
        assertThatThrownBy(useConnection::run).isInstanceOf(NonTransactionalConnectionException.class);
        assertThat(provided).hasSize(1);
        assertThat(provided.get(0).isClosed()).isTrue();
    }

    private static UnitOfWorkFactory unitOfWorkFactory(DataSource transactionDataSource, ConnectionProvider provider) {
        SpringTransactionManager transactionManager =
                new SpringTransactionManager(new DataSourceTransactionManager(transactionDataSource), null, provider);

        return new TransactionalUnitOfWorkFactory(transactionManager,
                                                  new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE));
    }

    private static String uniqueDbName() {
        return "guard" + UUID.randomUUID().toString().replace("-", "");
    }

    private static DataSource dataSource(String db) {
        JDBCDataSource dataSource = new JDBCDataSource();

        dataSource.setUrl("jdbc:hsqldb:mem:" + db);
        dataSource.setUser("SA");
        dataSource.setPassword("");

        return dataSource;
    }
}

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
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;

/**
 * Checks whether the connections of a {@link ConnectionProvider} take part in the transactions of a
 * {@link PlatformTransactionManager}.
 * <p>
 * Connections outside the transaction commit each statement on their own, so writes made through them, like token and
 * dead-letter updates, are not atomic with the rest of the unit of work. Only configurations whose
 * {@link DataSource DataSources} can be determined up front are checked: a {@link SpringDataSourceConnectionProvider}
 * combined with a {@link DataSourceTransactionManager} or {@code JpaTransactionManager}.
 *
 * @author Mitchell Herrijgers
 * @since 5.4.0
 */
final class TransactionalDataSourceCheck {

    private static final boolean JPA_PRESENT = ClassUtils.isPresent(
            "org.springframework.orm.jpa.JpaTransactionManager", TransactionalDataSourceCheck.class.getClassLoader()
    );

    private TransactionalDataSourceCheck() {
        // Utility class
    }

    /**
     * Returns why connections of the given {@code connectionProvider} do not take part in the transactions of the given
     * {@code transactionManager}, or {@code null} when they do or when that cannot be determined.
     *
     * @param transactionManager The transaction manager starting the transactions.
     * @param connectionProvider The connection provider whose connections should take part in them.
     * @return Why the connections do not take part in the transactions, or {@code null}.
     */
    static @Nullable String nonTransactionalReason(PlatformTransactionManager transactionManager,
                                                   @Nullable ConnectionProvider connectionProvider) {
        if (!(connectionProvider instanceof SpringDataSourceConnectionProvider springConnectionProvider)) {
            return null;
        }
        DataSource connectionDataSource = springConnectionProvider.dataSource();
        if (transactionManager instanceof DataSourceTransactionManager dataSourceTransactionManager) {
            return compare(transactionManager, dataSourceTransactionManager.getDataSource(), connectionDataSource);
        }
        if (JPA_PRESENT && JpaSupport.isJpaTransactionManager(transactionManager)) {
            DataSource managedDataSource = JpaSupport.dataSourceOf(transactionManager);
            if (managedDataSource == null) {
                return "The JpaTransactionManager does not know its DataSource, so it cannot share its JDBC "
                        + "connection with the ConnectionProvider. This happens when it is given the native "
                        + "EntityManagerFactory instead of the Spring-managed one. Pass it the EntityManagerFactory "
                        + "bean created by Spring, or set its DataSource.";
            }
            return compare(transactionManager, managedDataSource, connectionDataSource);
        }
        return null;
    }

    private static @Nullable String compare(PlatformTransactionManager transactionManager,
                                            @Nullable DataSource managedDataSource,
                                            DataSource connectionDataSource) {
        if (managedDataSource != null && sameResource(managedDataSource, connectionDataSource)) {
            return null;
        }
        return "The " + transactionManager.getClass().getSimpleName() + " manages DataSource [" + managedDataSource
                + "], but the ConnectionProvider uses DataSource [" + connectionDataSource + "]. "
                + "Transactions are bound to a DataSource instance, so this is the case even when both point to the "
                + "same database. Use the same DataSource bean for both.";
    }

    /**
     * Compares the data sources the way Spring binds transactional resources, unwrapping infrastructure proxies.
     */
    private static boolean sameResource(DataSource managedDataSource, DataSource connectionDataSource) {
        return TransactionSynchronizationUtils.unwrapResourceIfNecessary(managedDataSource)
                == TransactionSynchronizationUtils.unwrapResourceIfNecessary(transactionalTarget(connectionDataSource));
    }

    /**
     * A {@link TransactionAwareDataSourceProxy} hands out the transactional connection of the data source it wraps, so
     * its target is what the transaction manager needs to manage. Other proxies, like a
     * {@link org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy}, obtain connections from their target
     * directly, outside the transaction, so they are not unwrapped.
     */
    private static DataSource transactionalTarget(DataSource dataSource) {
        DataSource current = dataSource;
        while (current instanceof TransactionAwareDataSourceProxy proxy && proxy.getTargetDataSource() != null) {
            current = proxy.getTargetDataSource();
        }
        return current;
    }

    /**
     * Keeps references to the optional spring-orm module out of {@link TransactionalDataSourceCheck}.
     */
    private static final class JpaSupport {

        private static boolean isJpaTransactionManager(PlatformTransactionManager transactionManager) {
            return transactionManager instanceof JpaTransactionManager;
        }

        private static @Nullable DataSource dataSourceOf(PlatformTransactionManager transactionManager) {
            return ((JpaTransactionManager) transactionManager).getDataSource();
        }
    }
}

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

package org.axonframework.extension.springboot.autoconfig;

import org.axonframework.common.jdbc.ConnectionProvider;
import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.extension.spring.messaging.unitofwork.SpringTransactionManager;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.lang.Nullable;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Autoconfiguration class that registers a bean creation method for the {@link SpringTransactionManager} if a
 * {@link PlatformTransactionManager} and a {@link DataSource} is present.
 *
 * @author John Hendrikx
 * @since 5.0.2
 */
@AutoConfiguration(
        after = JpaTransactionAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"
        }
)
@ConditionalOnBean({DataSource.class, PlatformTransactionManager.class})
public class JdbcTransactionAutoConfiguration {

    /**
     * Bean creation method constructing a {@link SpringTransactionManager} based on the given
     * {@code transactionManager}.
     *
     * @param transactionManager    The {@code PlatformTransactionManager} used to construct a
     *                              {@link SpringTransactionManager}.
     * @param entityManagerProvider An optional entity manager provider.
     * @param connectionProvider    An optional connection provider.
     * @return The {@link TransactionManager} to be used by Axon Framework.
     */
    @Bean
    @ConditionalOnMissingBean
    public TransactionManager axonTransactionManager(
            PlatformTransactionManager transactionManager,
            @Nullable EntityManagerProvider entityManagerProvider,
            @Nullable ConnectionProvider connectionProvider
    ) {
        return new SpringTransactionManager(transactionManager, entityManagerProvider, connectionProvider);
    }
}

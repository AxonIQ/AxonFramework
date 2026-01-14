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

package org.axonframework.extension.micronaut.autoconfig;

import jakarta.persistence.EntityManagerFactory;
import org.axonframework.extension.micronaut.messaging.unitofwork.MicronautTransactionManager;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Autoconfiguration class that registers a bean creation method for the {@link MicronautTransactionManager} if a
 * {@link PlatformTransactionManager} is present.
 *
 * @author Allard Buijze
 * @since 3.0.3
 */
@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)
@ConditionalOnBean({EntityManagerFactory.class, PlatformTransactionManager.class})
public class TransactionAutoConfiguration {

    /**
     * Bean creation method constructing a {@link MicronautTransactionManager} based on the given
     * {@code transactionManager}.
     *
     * @param transactionManager The {@code PlatformTransactionManager} used to construct a
     *                           {@link MicronautTransactionManager}.
     * @return The {@link TransactionManager} to be used by Axon Framework.
     */
    @Bean
    @ConditionalOnMissingBean
    public TransactionManager axonTransactionManager(PlatformTransactionManager transactionManager) {
        return new MicronautTransactionManager(transactionManager);
    }
}

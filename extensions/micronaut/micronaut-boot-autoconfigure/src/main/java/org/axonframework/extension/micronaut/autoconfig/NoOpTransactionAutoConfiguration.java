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

import org.axonframework.messaging.core.unitofwork.transaction.NoTransactionManager;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Autoconfiguration class that registers a bean creation method for a default {@link TransactionManager}, the
 * {@link NoTransactionManager}.
 *
 * @author Allard Buijze
 * @since 3.0.3
 */
@AutoConfiguration
@AutoConfigureAfter(TransactionAutoConfiguration.class)
public class NoOpTransactionAutoConfiguration {

    /**
     * Bean creation method constructing the default {@link TransactionManager} to be used by Axon Framework.
     * <p>
     * The default is a {@link NoTransactionManager}.
     *
     * @return The {@link TransactionManager} to be used by Axon Framework.
     */
    @Bean
    @ConditionalOnMissingBean(TransactionManager.class)
    public TransactionManager axonTransactionManager() {
        return NoTransactionManager.INSTANCE;
    }
}

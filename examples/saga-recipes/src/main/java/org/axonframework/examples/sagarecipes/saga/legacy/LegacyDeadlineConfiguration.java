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


package org.axonframework.examples.sagarecipes.saga.legacy;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.deadline.SimpleDeadlineManager;
import org.axonframework.messaging.ScopeAwareProvider;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the {@link DeadlineManager} the legacy {@link PaymentSaga} schedules its payment timeout with.
 * <p>
 * The bean is the one the bike rental sample application declared, with the two changes Axon Framework 5 asks for.
 * The {@link ScopeAwareProvider} is the bean {@code axoniq-legacy} registers, because the
 * {@code ConfigurationScopeAwareProvider} of Axon Framework 4 is not ported. And the deadline's unit of work comes from
 * the configuration's {@link UnitOfWorkFactory}, where Axon Framework 4 took a {@code TransactionManager}: that factory
 * is what makes a fired deadline run in the same kind of unit of work as any other message, with the Saga's
 * dependencies resolvable.
 * <p>
 * Like the Saga, it only exists when the {@code legacy} recipe is selected. No other recipe has a deadline manager,
 * and none of them needs one.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Configuration
@ConditionalOnProperty(name = "saga.recipe", havingValue = "legacy")
class LegacyDeadlineConfiguration {

    /**
     * Creates a deadline manager that fires deadlines on a scheduler thread, in memory.
     * <p>
     * The destroy method is switched off, as in the original: the configuration's lifecycle shuts the deadline manager
     * down before the components that handle its deadlines.
     *
     * @param scopeAwareProvider the provider that finds the Saga a fired deadline belongs to
     * @param configuration      the Axon configuration, which holds the {@link UnitOfWorkFactory}
     * @return the deadline manager
     */
    @Bean(destroyMethod = "")
    DeadlineManager deadlineManager(ScopeAwareProvider scopeAwareProvider,
                                    AxonConfiguration configuration) {
        return SimpleDeadlineManager.builder()
                                    .scopeAwareProvider(scopeAwareProvider)
                                    .unitOfWorkFactory(configuration.getComponent(UnitOfWorkFactory.class))
                                    .build();
    }
}

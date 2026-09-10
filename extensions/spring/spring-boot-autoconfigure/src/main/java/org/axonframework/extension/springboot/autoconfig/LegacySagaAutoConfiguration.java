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

import org.axonframework.extension.spring.config.SpringSagaLookup;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;

/**
 * Autoconfiguration class enabling Spring Boot support for Axon Framework 4 {@link
 * org.axonframework.spring.stereotype.Saga}-annotated types, ported into {@code axon-legacy}.
 * <p>
 * Registers the {@link SpringSagaLookup} that discovers {@code @Saga}-annotated beans, and provides an
 * {@link InMemorySagaStore} as a last-resort {@link SagaStore} bean. Without any {@link SagaStore} component,
 * {@code Sagas.of(...)} refuses to build a Saga's {@link org.axonframework.messaging.eventhandling.EventHandlingComponent}
 * at all, so a store is a hard requirement, not merely a convenience. Axon Framework 4 hid this same default inside
 * its {@code SagaConfigurer}; here it is an explicit, overridable bean instead.
 * <p>
 * Activates only when {@code axon-legacy} is present on the classpath (evidenced by {@link SagaStore} being
 * loadable). {@link LegacyJpaSagaStoreAutoConfiguration} and {@link LegacyJdbcSagaStoreAutoConfiguration} run
 * before this class so their {@link SagaStore} beans, when applicable, take precedence over the in-memory
 * fallback.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@AutoConfiguration(afterName = {
        "org.axonframework.extension.springboot.autoconfig.LegacyJpaSagaStoreAutoConfiguration",
        "org.axonframework.extension.springboot.autoconfig.LegacyJdbcSagaStoreAutoConfiguration"
})
@ConditionalOnClass(SagaStore.class)
public class LegacySagaAutoConfiguration {

    /**
     * Provides the {@link SpringSagaLookup} that discovers {@code @Saga}-annotated bean definitions and registers a
     * {@link org.axonframework.extension.spring.config.SpringSagaConfigurer} for each of them.
     * <p>
     * Static, like {@link InfrastructureAutoConfiguration#messageHandlerLookup()}, so it can run as a
     * {@link org.springframework.beans.factory.config.BeanFactoryPostProcessor} before other bean definitions in
     * this context are fully processed.
     *
     * @return the lookup that discovers {@code @Saga}-annotated beans for later configuration
     */
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @Bean
    public static SpringSagaLookup springSagaLookup() {
        return new SpringSagaLookup();
    }

    /**
     * Provides an {@link InMemorySagaStore} when no other {@link SagaStore} bean is present.
     * <p>
     * This is the final fallback in the precedence chain: a user-defined bean wins over
     * {@link LegacyJpaSagaStoreAutoConfiguration}'s {@code JpaSagaStore}, which wins over
     * {@link LegacyJdbcSagaStoreAutoConfiguration}'s {@code JdbcSagaStore}, which wins over this in-memory store.
     *
     * @return an in-memory {@link SagaStore}
     */
    @Bean
    @ConditionalOnMissingBean(SagaStore.class)
    public InMemorySagaStore sagaStore() {
        return new InMemorySagaStore();
    }
}

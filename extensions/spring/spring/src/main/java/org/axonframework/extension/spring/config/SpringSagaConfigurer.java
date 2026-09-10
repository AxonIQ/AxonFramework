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

package org.axonframework.extension.spring.config;

import org.axonframework.common.StringUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.modelling.saga.SagaInstantiationException;
import org.axonframework.modelling.saga.configuration.Sagas;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Describes an Axon Framework 4 Saga discovered in a Spring application context. The descriptor contributes the
 * already assembled Saga manager to the regular Spring event processor configuration, allowing Sagas and ordinary
 * event handlers to share a processor as they did in Axon Framework 4.
 * <p>
 * This class is internal wiring created by {@link SpringSagaLookup}. Spring discovers only the Saga type; Axon creates
 * Saga instances itself, without applying Spring bean post-processors or injecting Saga fields.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Internal
public class SpringSagaConfigurer implements PreconfiguredEventHandlerDescriptor, ApplicationContextAware {

    private final String sagaBeanName;
    private final Class<?> sagaType;
    private final BeanDefinition sagaBeanDefinition;

    private @Nullable String sagaStore;
    private @Nullable ApplicationContext applicationContext;

    /**
     * Initializes a descriptor for the given Saga type.
     *
     * @param sagaType the Saga type to configure
     */
    public SpringSagaConfigurer(Class<?> sagaType) {
        this(sagaType.getName(), sagaType,
             () -> BeanDefinitionBuilder.genericBeanDefinition(sagaType).getBeanDefinition());
    }

    /**
     * Initializes a descriptor for a discovered Saga bean.
     *
     * @param sagaBeanName               the Spring bean name used for discovery and processor selectors
     * @param sagaType                   the Saga type to configure
     * @param sagaBeanDefinitionSupplier supplies the original Spring bean definition without making Spring treat it
     *                                   as an inner bean
     */
    public SpringSagaConfigurer(String sagaBeanName,
                                Class<?> sagaType,
                                Supplier<BeanDefinition> sagaBeanDefinitionSupplier) {
        this.sagaBeanName = Objects.requireNonNull(sagaBeanName, "The sagaBeanName must not be null.");
        this.sagaType = Objects.requireNonNull(sagaType, "The sagaType must not be null.");
        this.sagaBeanDefinition = Objects.requireNonNull(sagaBeanDefinitionSupplier,
                                                        "The sagaBeanDefinitionSupplier must not be null.")
                                           .get();
    }

    /**
     * Sets the name of the {@link SagaStore} bean the Sagas of this type are kept in.
     *
     * @param sagaStore the name of the Saga store bean
     */
    public void setSagaStore(String sagaStore) {
        this.sagaStore = sagaStore;
    }

    @Override
    public String beanName() {
        return sagaBeanName;
    }

    @Override
    public BeanDefinition beanDefinition() {
        return sagaBeanDefinition;
    }

    @Override
    public Class<?> beanType() {
        return sagaType;
    }

    @Override
    public Object resolveBean() {
        try {
            return sagaType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new SagaInstantiationException("Exception while trying to instantiate a new Saga", e);
        }
    }

    @Override
    public ComponentBuilder<EventHandlingComponent> eventHandlingComponent() {
        return sagaComponent(sagaType);
    }

    @Override
    public Optional<String> preferredProcessorName() {
        return Optional.of(sagaType.getSimpleName() + "Processor");
    }

    @Override
    public UnaryOperator<PooledStreamingEventProcessorConfiguration> pooledStreamingDefaults() {
        return configuration -> configuration.initialToken(source -> source.latestToken(null));
    }

    @Override
    public String deduplicationKey() {
        return sagaType.getName();
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    private <T> ComponentBuilder<EventHandlingComponent> sagaComponent(Class<T> type) {
        if (StringUtils.emptyOrNull(sagaStore)) {
            return Sagas.of(type);
        }
        String sagaStoreBeanName = sagaStore;
        ApplicationContext context = requireApplicationContext();
        ComponentBuilder<SagaStore<? super T>> storeBuilder =
                configuration -> sagaStore(context, sagaStoreBeanName);
        return Sagas.of(type, storeBuilder);
    }

    @SuppressWarnings("unchecked")
    private static <T> SagaStore<? super T> sagaStore(ApplicationContext context, String beanName) {
        return (SagaStore<? super T>) context.getBean(beanName, SagaStore.class);
    }

    private ApplicationContext requireApplicationContext() {
        return Objects.requireNonNull(applicationContext,
                                      "The ApplicationContext must be set before resolving Saga components.");
    }
}

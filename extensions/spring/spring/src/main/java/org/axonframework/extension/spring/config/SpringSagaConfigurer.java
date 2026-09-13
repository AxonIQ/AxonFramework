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
import org.axonframework.modelling.saga.configuration.Sagas;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Describes an Axon Framework 4 Saga discovered in a Spring application context, carrying everything
 * {@link SagaProcessorConfigurer} needs to assemble that Saga's event processor.
 * <p>
 * Implements {@link EventProcessorDefinition.EventHandlerDescriptor} so that an
 * {@link EventProcessorDefinition}'s handler selector can pick a Saga the same way it picks an ordinary event
 * handler bean. The Saga itself is assembled separately from ordinary handlers, though: see
 * {@link SagaProcessorConfigurer}.
 * <p>
 * This class is internal wiring created by {@link SpringSagaLookup}. Spring discovers only the Saga type; Axon creates
 * Saga instances itself, without applying Spring bean post-processors or injecting Saga fields.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Internal
public class SpringSagaConfigurer
        implements EventProcessorDefinition.EventHandlerDescriptor, ApplicationContextAware {

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

    /**
     * Always throws, because a Saga has no single bean instance to resolve.
     * <p>
     * Spring never instantiates a Saga: instances are created and loaded per Saga identifier by the Saga manager.
     * Returning a fresh throwaway instance here would look like the bean an
     * {@link EventProcessorDefinition} selector asked for while belonging to no Saga at all. Select a Saga on
     * {@link #beanName()} or {@link #beanType()} instead.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public Object resolveBean() {
        throw new UnsupportedOperationException(
                "Cannot resolve a bean instance for Saga [" + sagaType.getName()
                        + "]. Saga instances are managed by the Saga manager, one per Saga identifier, not by Spring. "
                        + "Select a Saga by its bean name or bean type instead.");
    }

    @Override
    public ComponentBuilder<Object> component() {
        return eventHandlingComponent()::build;
    }

    /**
     * Returns the builder for the already assembled Saga manager, to be registered declaratively rather than
     * inspected for annotated handler methods.
     *
     * @return the event handling component builder wrapping this Saga's manager
     */
    ComponentBuilder<EventHandlingComponent> eventHandlingComponent() {
        return sagaComponent(sagaType);
    }

    /**
     * Returns the processor name derived from the Saga type, used when neither a selector nor a
     * {@link org.axonframework.messaging.core.annotation.Namespace} assigns the Saga.
     *
     * @return the derived processor name, {@code <SagaSimpleName>Processor}
     */
    String derivedProcessorName() {
        return sagaType.getSimpleName() + "Processor";
    }

    /**
     * Returns the key collapsing repeated registrations of the same Saga type, so that declaring one Saga twice
     * yields a single component rather than two.
     *
     * @return the deduplication key
     */
    String deduplicationKey() {
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

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

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.StringUtils;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.modelling.saga.configuration.Sagas;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.spring.stereotype.Saga;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static java.lang.String.format;

/**
 * The descriptor of a {@link Saga @Saga} annotated bean, contributed by the {@link SpringSagaLookup}.
 * <p>
 * A Saga is not an event handling bean whose handlers can be uncovered by annotation inspection: an
 * {@code AnnotatedSagaManager} decides for itself which events reach which Saga instance, and it needs a
 * {@code SagaRepository} and a {@link SagaStore} underneath it. {@link Sagas} builds that assembly, and this
 * descriptor carries it into the dedicated, Saga-only event processor {@link SagaProcessorConfigurer} builds for it --
 * configured through {@code axon.eventhandling.processors} exactly like any other processor, but never shared with
 * another Saga or a regular event handler.
 * <p>
 * Shaped like an {@link EventProcessorDefinition.EventHandlerDescriptor} -- {@link #beanName()}, {@link #beanType()}
 * and the rest -- so {@link SagaProcessorConfigurer} can wrap it as one and hand it to the same
 * {@link DefaultProcessorModuleFactory} the plain event handling beans go through, rather than reimplementing its
 * processor-name resolution and settings application. Deliberately does not implement the interface itself: a Spring
 * bean of a type assignable to {@link EventProcessorDefinition.EventHandlerDescriptor} is exactly what
 * {@link MessageHandlerConfigurer} auto-discovers for the plain event handling pipeline, and a Saga must never be
 * assigned a processor through that pipeline instead of {@link SagaProcessorConfigurer}'s own.
 * <p>
 * Instances are bean definitions registered by {@link SpringSagaLookup}, one per {@code @Saga} bean. This class is
 * internal: an application declares {@code @Saga} and never touches this.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Internal
public class SpringSagaDescriptor {

    /**
     * The bean name a {@link SagaStore} is resolved under when the {@link Saga#sagaStore()} attribute is unset and the
     * application declares more than one store.
     * <p>
     * Both the JPA and JDBC auto configurations register their Saga store under this name, which was also the name
     * Axon Framework 4 gave it, so an application declaring several stores keeps resolving the one it resolved before.
     */
    static final String CONVENTIONAL_SAGA_STORE_BEAN_NAME = "sagaStore";

    private final String sagaBeanName;
    private final Class<?> sagaType;
    private final ConfigurableListableBeanFactory beanFactory;
    private @Nullable String sagaStore;

    /**
     * Initializes a descriptor for the Saga of the given {@code sagaType}, held by the bean named
     * {@code sagaBeanName}.
     *
     * @param sagaBeanName the name of the bean holding the Saga
     * @param sagaType     the type of Saga to describe
     * @param beanFactory  the bean factory the Saga bean is registered in, used to resolve its own
     *                     {@link BeanDefinition} for processor-name resolution
     */
    public SpringSagaDescriptor(String sagaBeanName, Class<?> sagaType, ConfigurableListableBeanFactory beanFactory) {
        this.sagaBeanName = Objects.requireNonNull(sagaBeanName, "The sagaBeanName may not be null.");
        this.sagaType = Objects.requireNonNull(sagaType, "The sagaType may not be null.");
        this.beanFactory = Objects.requireNonNull(beanFactory, "The beanFactory may not be null.");
    }

    /**
     * Sets the bean name of the {@link SagaStore} to configure.
     *
     * @param sagaStore the bean name of the {@link SagaStore} to configure.
     */
    public void setSagaStore(String sagaStore) {
        this.sagaStore = sagaStore;
    }

    /**
     * The name of the bean holding the Saga.
     *
     * @return the Saga bean name
     */
    public String beanName() {
        return sagaBeanName;
    }

    /**
     * The Saga bean's own {@link BeanDefinition}, not this descriptor's -- so a
     * {@link org.axonframework.messaging.core.annotation.Namespace @Namespace} or package-name fallback resolves
     * against the Saga type itself.
     *
     * @return the Saga bean's bean definition
     */
    public BeanDefinition beanDefinition() {
        return beanFactory.getBeanDefinition(sagaBeanName);
    }

    /**
     * The type of Saga this descriptor describes.
     *
     * @return the Saga type
     */
    public Class<?> beanType() {
        return sagaType;
    }

    /**
     * Resolves the Saga bean itself, exactly as any other prototype-scoped Spring bean would.
     * <p>
     * Unlike {@link #component()}, this does not go through {@link Sagas#of}: the assembled handling component needs
     * the Axon {@link Configuration} to resolve its {@link SagaStore}, which this method, matching the rest of
     * {@link EventProcessorDefinition.EventHandlerDescriptor}, has no access to.
     *
     * @return a fresh Saga bean instance
     */
    public Object resolveBean() {
        return beanFactory.getBean(sagaBeanName);
    }

    /**
     * Builds the assembled Saga handling component.
     * <p>
     * Already an {@link EventHandlingComponent}, not an annotated bean to inspect -- {@link DefaultProcessorModuleFactory}
     * registers it declaratively rather than through annotation inspection.
     *
     * @return the Saga handling component builder
     */
    public ComponentBuilder<Object> component() {
        ComponentBuilder<EventHandlingComponent> handlingComponent = sagaComponent(sagaType);
        return handlingComponent::build;
    }

    private <T> ComponentBuilder<EventHandlingComponent> sagaComponent(Class<T> type) {
        return Sagas.of(type, () -> BeanUtils.instantiateClass(type), this::resolveSagaStore);
    }

    /**
     * Returns the processor name to use when no {@link org.axonframework.messaging.core.annotation.Namespace} on the
     * Saga type overrides it.
     * <p>
     * The name Axon Framework 4 derived from the Saga type. Preserving it is what keeps a migrating application's
     * token store row claimable and its {@code axon.eventhandling.processors.<SagaName>Processor} settings in effect.
     *
     * @return the preferred fallback processor name
     */
    public Optional<String> preferredProcessorName() {
        return Optional.of(sagaType.getSimpleName() + "Processor");
    }

    /**
     * Returns defaults to apply when this Saga's processor is otherwise unconfigured.
     * <p>
     * Starts a Saga processor at the head of the event stream, as Axon Framework 4 did. Replaying an existing stream
     * from the start would create a Saga instance per historic event, so the Axon Framework 5 default of the first
     * token is the wrong one for a Saga that is only now being deployed.
     *
     * @return the pooled streaming processor defaults
     */
    public UnaryOperator<PooledStreamingEventProcessorConfiguration> pooledStreamingDefaults() {
        return configuration -> configuration.initialToken(source -> source.latestToken(null));
    }

    /**
     * The {@link SagaStore} the Sagas of this type are kept in: the bean named on the annotation when there is one,
     * otherwise the bean under the {@link #CONVENTIONAL_SAGA_STORE_BEAN_NAME conventional name}, and failing that the
     * single store bean.
     * <p>
     * Preferring the conventional name before the type-level lookup mirrors how a
     * {@link org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore} is resolved for an
     * event processor. Without it, an application declaring a second store for one Saga would stop being able to
     * resolve a store for all the others, which is not what Axon Framework 4 did.
     * <p>
     * A named lookup goes through the given {@code configuration} rather than a Spring {@code ApplicationContext}:
     * Spring's own bean factory backs it, so a named Spring bean resolves exactly the same way, without this
     * descriptor needing an {@code ApplicationContext} reference of its own.
     */
    @SuppressWarnings("unchecked")
    private SagaStore<Object> resolveSagaStore(Configuration configuration) {
        if (StringUtils.nonEmptyOrNull(sagaStore)) {
            return (SagaStore<Object>) configuration.getComponent(SagaStore.class, sagaStore);
        }
        return (SagaStore<Object>) configuration
                .getOptionalComponent(SagaStore.class, CONVENTIONAL_SAGA_STORE_BEAN_NAME)
                .or(() -> configuration.getOptionalComponent(SagaStore.class))
                .orElseThrow(() -> new AxonConfigurationException(format(
                        "No component of type [%s] is registered, so the sagas of type [%s] have nowhere to be stored.",
                        SagaStore.class.getName(),
                        sagaType.getName()
                )));
    }
}

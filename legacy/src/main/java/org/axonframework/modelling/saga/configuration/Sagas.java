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

package org.axonframework.modelling.saga.configuration;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.modelling.saga.AnnotatedSagaManager;
import org.axonframework.modelling.saga.repository.AnnotatedSagaRepository;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static java.lang.String.format;

/**
 * Builders turning a Saga type into the {@link EventHandlingComponent} an event processor expects.
 * <p>
 * An {@link AnnotatedSagaManager} is an {@code EventHandlingComponent}, so a Saga needs no dedicated configuration
 * construct: it is registered on an
 * {@link org.axonframework.messaging.eventhandling.configuration.EventProcessorModule EventProcessorModule} like any
 * other component, and inherits everything the processor offers. What that leaves a user to write by hand is the
 * manager and the {@link AnnotatedSagaRepository} underneath it, wired to the {@link SagaStore} and the reflection
 * components of the running {@link Configuration}. That assembly is what this class provides:
 * <pre>{@code
 * MessagingConfigurer.create()
 *                    .componentRegistry(cr -> cr.registerComponent(SagaStore.class, c -> new InMemorySagaStore()))
 *                    .eventProcessing(processing -> processing.subscribing(
 *                            subscribing -> subscribing.defaultProcessor(
 *                                    "orders",
 *                                    components -> components.declarative("Saga[OrderSaga]",
 *                                                                         Sagas.of(OrderSaga.class)))));
 * }</pre>
 * Because the result is an ordinary component builder, a processor can carry several Sagas next to other event
 * handling components, and the whole set can be decorated:
 * <pre>{@code
 * components -> components.declarative("Saga[OrderSaga]", Sagas.of(OrderSaga.class))
 *                         .declarative("Saga[ShipmentSaga]", Sagas.of(ShipmentSaga.class))
 *                         .withExceptionHandler(c -> loggingExceptionHandler)
 * }</pre>
 * A subscribing processor keeps handling on the publishing thread and inside the publisher's
 * {@link org.axonframework.messaging.core.unitofwork.ProcessingContext ProcessingContext}, which is what
 * {@link AnnotatedSagaRepository#WRITE_SAGA} was ordered for. A pooled streaming processor works equally well, and is
 * the choice for a Saga that has to keep up with a stream rather than with its publisher.
 * <p>
 * Sagas carry the Axon Framework 4 API, to ease migration of projects that cannot move off it in one go.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
public final class Sagas {

    private Sagas() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * A builder of the {@link EventHandlingComponent} handling events for Sagas of the given {@code sagaType},
     * constructing each instance through the Saga's no-argument constructor.
     *
     * @param sagaType the type of Saga the resulting component manages
     * @param <T>      the type of Saga the resulting component manages
     * @return a builder of the {@link EventHandlingComponent} handling events for Sagas of the given {@code sagaType}
     */
    public static <T> ComponentBuilder<EventHandlingComponent> of(Class<T> sagaType) {
        Objects.requireNonNull(sagaType, "The sagaType may not be null.");
        return configuration -> managerFor(sagaType, null, sagaStoreOf(configuration, sagaType), configuration);
    }

    /**
     * A builder of the {@link EventHandlingComponent} handling events for Sagas of the given {@code sagaType},
     * constructing each instance through the given {@code sagaFactory}.
     * <p>
     * Use this when a Saga needs a collaborator its handler methods cannot receive as a parameter, or when it lacks a
     * no-argument constructor.
     *
     * @param sagaType    the type of Saga the resulting component manages
     * @param sagaFactory the factory constructing a new Saga instance
     * @param <T>         the type of Saga the resulting component manages
     * @return a builder of the {@link EventHandlingComponent} handling events for Sagas of the given {@code sagaType}
     */
    public static <T> ComponentBuilder<EventHandlingComponent> of(Class<T> sagaType, Supplier<T> sagaFactory) {
        Objects.requireNonNull(sagaType, "The sagaType may not be null.");
        Objects.requireNonNull(sagaFactory, "The sagaFactory may not be null.");
        return configuration -> managerFor(sagaType, sagaFactory, sagaStoreOf(configuration, sagaType), configuration);
    }

    /**
     * A builder of the {@link EventHandlingComponent} handling events for Sagas of the given {@code sagaType},
     * constructing each instance through the Saga's no-argument constructor and storing them in the given
     * {@code sagaStore}.
     * <p>
     * Use this when the Sagas of this type belong in another store than the one registered as a component, without
     * having to also supply a Saga factory just to reach that store.
     *
     * @param sagaType  the type of Saga the resulting component manages
     * @param sagaStore a builder of the store the Sagas of this type are kept in
     * @param <T>       the type of Saga the resulting component manages
     * @return a builder of the {@link EventHandlingComponent} handling events for Sagas of the given {@code sagaType}
     */
    public static <T> ComponentBuilder<EventHandlingComponent> of(
            Class<T> sagaType,
            ComponentBuilder<SagaStore<? super T>> sagaStore
    ) {
        Objects.requireNonNull(sagaType, "The sagaType may not be null.");
        Objects.requireNonNull(sagaStore, "The sagaStore may not be null.");
        return configuration -> managerFor(sagaType, null, sagaStore.build(configuration), configuration);
    }

    /**
     * A builder of the {@link EventHandlingComponent} handling events for Sagas of the given {@code sagaType},
     * constructing each instance through the given {@code sagaFactory} and storing them in the given
     * {@code sagaStore}.
     * <p>
     * Use this when the Sagas of this type belong in another store than the one registered as a component, for
     * instance because two Saga types are kept apart.
     *
     * @param sagaType    the type of Saga the resulting component manages
     * @param sagaFactory the factory constructing a new Saga instance
     * @param sagaStore   a builder of the store the Sagas of this type are kept in
     * @param <T>         the type of Saga the resulting component manages
     * @return a builder of the {@link EventHandlingComponent} handling events for Sagas of the given {@code sagaType}
     */
    public static <T> ComponentBuilder<EventHandlingComponent> of(
            Class<T> sagaType,
            Supplier<T> sagaFactory,
            ComponentBuilder<SagaStore<? super T>> sagaStore
    ) {
        Objects.requireNonNull(sagaType, "The sagaType may not be null.");
        Objects.requireNonNull(sagaFactory, "The sagaFactory may not be null.");
        Objects.requireNonNull(sagaStore, "The sagaStore may not be null.");
        return configuration -> managerFor(sagaType, sagaFactory, sagaStore.build(configuration), configuration);
    }

    private static <T> EventHandlingComponent managerFor(Class<T> sagaType,
                                                         @Nullable Supplier<T> sagaFactory,
                                                         SagaStore<? super T> sagaStore,
                                                         Configuration configuration) {
        // Both are left to the builders' own classpath defaults when the configuration holds none, which is what an
        // AnnotatedSagaManager assembled by hand would fall back to.
        Optional<ParameterResolverFactory> parameterResolverFactory =
                configuration.getOptionalComponent(ParameterResolverFactory.class);
        Optional<HandlerDefinition> handlerDefinition =
                configuration.getOptionalComponent(HandlerDefinition.class);

        AnnotatedSagaRepository.Builder<T> repository = AnnotatedSagaRepository.<T>builder()
                                                                              .sagaType(sagaType)
                                                                              .sagaStore(sagaStore);
        parameterResolverFactory.ifPresent(repository::parameterResolverFactory);
        handlerDefinition.ifPresent(repository::handlerDefinition);

        AnnotatedSagaManager.Builder<T> manager = AnnotatedSagaManager.<T>builder()
                                                                      .sagaRepository(repository.build())
                                                                      .sagaType(sagaType);
        parameterResolverFactory.ifPresent(manager::parameterResolverFactory);
        handlerDefinition.ifPresent(manager::handlerDefinition);
        if (sagaFactory != null) {
            manager.sagaFactory(sagaFactory);
        }
        return manager.build();
    }

    @SuppressWarnings("unchecked")
    private static <T> SagaStore<? super T> sagaStoreOf(Configuration configuration, Class<T> sagaType) {
        return (SagaStore<? super T>) configuration
                .getOptionalComponent(SagaStore.class)
                .orElseThrow(() -> new AxonConfigurationException(format(
                        "No component of type [%s] is registered, so the sagas of type [%s] have nowhere to be stored.",
                        SagaStore.class.getName(),
                        sagaType.getName()
                )));
    }
}

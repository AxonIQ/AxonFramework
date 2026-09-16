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

package org.axonframework.config;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.modelling.saga.AbstractSagaManager;
import org.axonframework.modelling.saga.AnnotatedSagaManager;
import org.axonframework.modelling.saga.SagaRepository;
import org.axonframework.modelling.saga.repository.AnnotatedSagaRepository;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.lang.String.format;
import static org.axonframework.common.BuilderUtils.assertNonNull;

/**
 * Configures the components used to manage and store Sagas of a given type.
 * <p>
 * This configurer retains the Axon Framework 4 fluent configuration API while adapting it to Axon Framework 5 event
 * processing. It is a {@link ComponentBuilder} for the Saga's {@link EventHandlingComponent}, so it can be registered
 * declaratively on an event processor:
 * <pre>{@code
 * MessagingConfigurer.create()
 *                    .componentRegistry(cr -> cr.registerComponent(SagaStore.class, c -> new InMemorySagaStore()))
 *                    .eventProcessing(processing -> processing.subscribing(
 *                            subscribing -> subscribing.defaultProcessor(
 *                                    "orders",
 *                                    components -> components.declarative(
 *                                            "Saga[OrderSaga]",
 *                                            SagaConfigurer.forType(OrderSaga.class)))));
 * }</pre>
 * The first call to {@link #build(Configuration)} fixes this configurer's settings. Further configuration is rejected,
 * and repeated build calls return the same manager, matching the lifecycle of the Axon Framework 4 configurer.
 * <p>
 * A custom manager replaces the complete default assembly. A custom repository replaces the default repository and
 * its store dependency. Consequently, lower-level settings are only used when this configurer builds that level.
 *
 * @param <T> the Saga type under configuration
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @since 4.0
 */
public class SagaConfigurer<T> implements ComponentBuilder<EventHandlingComponent> {

    private final Class<T> type;

    private @Nullable Function<Configuration, AbstractSagaManager<T>> managerBuilder;
    private @Nullable Function<Configuration, SagaRepository<T>> repositoryBuilder;
    private Function<Configuration, SagaStore<? super T>> storeBuilder;
    private @Nullable Supplier<T> sagaFactory;
    private @Nullable AbstractSagaManager<T> sagaManager;
    private boolean initialized;

    /**
     * Retrieves a configurer for the given {@code sagaType}.
     *
     * @param sagaType the type of Saga to configure
     * @param <T>      the Saga type under configuration
     * @return a configurer for the given {@code sagaType}
     */
    public static <T> SagaConfigurer<T> forType(Class<T> sagaType) {
        return new SagaConfigurer<>(sagaType);
    }

    /**
     * Initializes a configurer for the given Saga type.
     *
     * @param type the type of Saga to configure
     */
    protected SagaConfigurer(Class<T> type) {
        assertNonNull(type, "Saga type is not allowed to be null");
        this.type = type;
        this.storeBuilder = configuration -> sagaStoreOf(configuration, type);
    }

    /**
     * Configures the Saga manager. Supplying a manager makes that builder responsible for the complete manager
     * assembly; configured repositories, stores, and Saga factories are not used.
     *
     * @param managerBuilder the function that builds the Saga manager
     * @return this configurer for fluent configuration
     */
    public SagaConfigurer<T> configureSagaManager(
            Function<Configuration, AbstractSagaManager<T>> managerBuilder
    ) {
        verifyNotInitialized();
        assertNonNull(managerBuilder, "SagaManager builder is not allowed to be null");
        this.managerBuilder = managerBuilder;
        return this;
    }

    /**
     * Configures the Saga repository. Supplying a repository makes that builder responsible for its store; a
     * separately configured Saga store is not used by the default manager.
     *
     * @param repositoryBuilder the function that builds the Saga repository
     * @return this configurer for fluent configuration
     */
    public SagaConfigurer<T> configureRepository(
            Function<Configuration, SagaRepository<T>> repositoryBuilder
    ) {
        verifyNotInitialized();
        assertNonNull(repositoryBuilder, "SagaRepository builder is not allowed to be null");
        this.repositoryBuilder = repositoryBuilder;
        return this;
    }

    /**
     * Configures the store used by the default Saga repository.
     *
     * @param storeBuilder the function that builds the Saga store
     * @return this configurer for fluent configuration
     */
    public SagaConfigurer<T> configureSagaStore(
            Function<Configuration, SagaStore<? super T>> storeBuilder
    ) {
        verifyNotInitialized();
        assertNonNull(storeBuilder, "SagaStore builder is not allowed to be null");
        this.storeBuilder = storeBuilder;
        return this;
    }

    /**
     * Configures the factory used by the default annotated Saga manager to create Saga instances.
     * <p>
     * Use this when a Saga has no accessible no-argument constructor or needs a collaborator that cannot be provided
     * as a handler method parameter.
     *
     * @param sagaFactory the factory that creates Saga instances
     * @return this configurer for fluent configuration
     */
    public SagaConfigurer<T> configureSagaFactory(Supplier<T> sagaFactory) {
        verifyNotInitialized();
        assertNonNull(sagaFactory, "Saga factory is not allowed to be null");
        this.sagaFactory = sagaFactory;
        return this;
    }

    /**
     * Builds the Saga manager for this configuration. The first invocation fixes the configured builders; subsequent
     * invocations return the same manager.
     *
     * @param configuration the configuration providing shared framework components
     * @return the Saga manager built by this configurer
     */
    @Override
    public AbstractSagaManager<T> build(Configuration configuration) {
        if (sagaManager == null) {
            initialized = true;
            Function<Configuration, AbstractSagaManager<T>> configuredManagerBuilder = managerBuilder;
            sagaManager = configuredManagerBuilder == null
                    ? buildDefaultManager(configuration)
                    : configuredManagerBuilder.apply(configuration);
        }
        return sagaManager;
    }

    private AbstractSagaManager<T> buildDefaultManager(Configuration configuration) {
        SagaRepository<T> repository = repositoryBuilder == null
                ? buildDefaultRepository(configuration)
                : repositoryBuilder.apply(configuration);

        AnnotatedSagaManager.Builder<T> manager = AnnotatedSagaManager.<T>builder()
                                                                      .sagaRepository(repository)
                                                                      .sagaType(type);
        reflectionComponentsOf(configuration).applyTo(manager);
        if (sagaFactory != null) {
            manager.sagaFactory(sagaFactory);
        }
        return manager.build();
    }

    private SagaRepository<T> buildDefaultRepository(Configuration configuration) {
        AnnotatedSagaRepository.Builder<T> repository = AnnotatedSagaRepository.<T>builder()
                                                                              .sagaType(type)
                                                                              .sagaStore(
                                                                                      storeBuilder.apply(configuration)
                                                                              );
        reflectionComponentsOf(configuration).applyTo(repository);
        return repository.build();
    }

    private void verifyNotInitialized() {
        if (initialized) {
            throw new AxonConfigurationException(
                    "SagaConfiguration has already been created. Cannot make modifications."
            );
        }
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

    private static ReflectionComponents reflectionComponentsOf(Configuration configuration) {
        return new ReflectionComponents(
                configuration.getOptionalComponent(ParameterResolverFactory.class),
                configuration.getOptionalComponent(HandlerDefinition.class)
        );
    }

    private record ReflectionComponents(Optional<ParameterResolverFactory> parameterResolverFactory,
                                        Optional<HandlerDefinition> handlerDefinition) {

        private <S> void applyTo(AnnotatedSagaRepository.Builder<S> builder) {
            parameterResolverFactory.ifPresent(builder::parameterResolverFactory);
            handlerDefinition.ifPresent(builder::handlerDefinition);
        }

        private <S> void applyTo(AnnotatedSagaManager.Builder<S> builder) {
            parameterResolverFactory.ifPresent(builder::parameterResolverFactory);
            handlerDefinition.ifPresent(builder::handlerDefinition);
        }
    }
}

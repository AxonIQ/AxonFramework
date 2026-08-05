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

package org.axonframework.messaging.core.unitofwork;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.DefaultComponentRegistry;
import org.axonframework.common.configuration.StubLifecycleRegistry;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.core.ApplicationContext;
import org.axonframework.messaging.core.ConfigurationApplicationContext;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.LegacyResources;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.Context;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Stubbed implementation of the {@link ProcessingContext} used for testing purposes.
 * <p>
 * The {@code putResource}/{@code updateResource}/{@code removeResource} operations mutate this instance, while
 * {@link #withResource(ResourceKey, Object)} branches: it returns a new context and leaves this one untouched.
 *
 * @author Allard Buijze
 */
public class StubProcessingContext implements ProcessingContext {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(StubProcessingContext.class);

    private final Map<ResourceKey<?>, Object> resources = new ConcurrentHashMap<>();
    private final Map<Phase, List<Function<ProcessingContext, CompletableFuture<?>>>> phaseActions = new ConcurrentHashMap<>();
    private Phase currentPhase = DefaultPhases.PRE_INVOCATION;
    private final ApplicationContext applicationContext;

    /**
     * Creates a new stub {@link ProcessingContext} with an empty {@link ApplicationContext}. You can use this to create
     * a context compatible with most of the framework. Do note that this context does not commit or advance phases on
     * its own, but you can use {@link #moveToPhase(Phase)} to advance the context to a specific phase.
     */
    public StubProcessingContext() {
        this(EmptyApplicationContext.INSTANCE);
    }

    /**
     * Creates a new stub {@link ProcessingContext} with the given {@link ApplicationContext}. You can use this to
     * create a context compatible with most of the framework. Do note that this context does not commit or advance
     * phases on its own, but you can use {@link #moveToPhase(Phase)} to advance the context to a specific phase.
     *
     * @param applicationContext The application context to use for this processing context.
     */
    public StubProcessingContext(@NonNull ApplicationContext applicationContext) {
        Objects.requireNonNull(applicationContext, "The application context may not be null");
        this.applicationContext = applicationContext;
    }

    @Override
    public boolean isStarted() {
        return false;
    }

    @Override
    public boolean isError() {
        return false;
    }

    @Override
    public boolean isCommitted() {
        return false;
    }

    @Override
    public boolean isCompleted() {
        return false;
    }

    public CompletableFuture<Object> moveToPhase(ProcessingLifecycle.@NonNull Phase phase) {
        if (phase.isBefore(currentPhase)) {
            throw new IllegalArgumentException("Cannot move to a phase before the current phase");
        }
        if (!phase.isAfter(currentPhase)) {
            return CompletableFuture.completedFuture(null);
        }
        ProcessingLifecycle.Phase initialPhase = currentPhase;
        CompletableFuture<Object> result = phaseActions.keySet().stream()
                                                       .filter(p -> p.isAfter(initialPhase)
                                                               && p.order() <= phase.order())
                                                       .sorted(Comparator.comparing(ProcessingLifecycle.Phase::order))
                                                       .flatMap(p -> phaseActions.get(p).stream())
                                                       .reduce(CompletableFuture.completedFuture(null),
                                                               (cf, action) -> cf.thenCompose(v -> (CompletableFuture<Object>) action.apply(
                                                                       this)),
                                                               (cf1, cf2) -> cf2);
        currentPhase = phase;
        return result;
    }

    @Override
    public @NonNull ProcessingLifecycle on(@NonNull Phase phase,
                                  @NonNull Function<ProcessingContext, CompletableFuture<?>> action) {
        if (phase.order() <= currentPhase.order()) {
            throw new IllegalArgumentException("Cannot register an action for a phase that has already passed");
        }
        phaseActions.computeIfAbsent(phase, p -> new CopyOnWriteArrayList<>()).add(action);
        return this;
    }

    @Override
    public @NonNull ProcessingLifecycle onError(@NonNull ErrorHandler action) {
        logger.warn("Error handler is not yet supported in the StubProcessingContext");
        return this;
    }

    @Override
    public @NonNull ProcessingLifecycle whenComplete(@NonNull Consumer<ProcessingContext> action) {
        logger.warn("Completion action is not yet supported in the StubProcessingContext");
        return this;
    }

    @Override
    public boolean containsResource(@NonNull ResourceKey<?> key) {
        return resources.containsKey(key);
    }

    @Override
    public <T> T getResource(@NonNull ResourceKey<T> key) {
        //noinspection unchecked
        return (T) resources.get(key);
    }

    @Override
    public Map<ResourceKey<?>, Object> resources() {
        return Map.copyOf(resources);
    }

    @Override
    public <T> T putResource(@NonNull ResourceKey<T> key,
                             @NonNull T resource) {
        //noinspection unchecked
        return (T) resources.put(key, resource);
    }

    @Override
    public <T> T updateResource(@NonNull ResourceKey<T> key,
                                @NonNull UnaryOperator<T> resourceUpdater) {
        //noinspection unchecked
        return (T) resources.compute(key, (id, current) -> resourceUpdater.apply((T) current));
    }

    @Override
    public <T> T putResourceIfAbsent(@NonNull ResourceKey<T> key,
                                     @NonNull T resource) {
        //noinspection unchecked
        return (T) resources.putIfAbsent(key, resource);
    }

    @Override
    public <T> T computeResourceIfAbsent(@NonNull ResourceKey<T> key,
                                         @NonNull Supplier<T> resourceSupplier) {
        //noinspection unchecked
        return (T) resources.computeIfAbsent(key, k -> resourceSupplier.get());
    }

    @Override
    public <T> T removeResource(@NonNull ResourceKey<T> key) {
        //noinspection unchecked
        return (T) resources.remove(key);
    }

    @Override
    public <T> boolean removeResource(@NonNull ResourceKey<T> key,
                                      @NonNull T expectedResource) {
        return resources.remove(key, expectedResource);
    }

    /**
     * Creates a new stub {@link ProcessingContext} for the given {@link EventMessage} and aggregate-specific
     * parameters. You can use this to create a context compatible with tests that are based on Aggregates.
     *
     * @param domainEventMessage the event message with aggregate id, sequence and type
     * @param aggregateId the aggregate id
     * @param seqNo the sequence number
     * @param aggregateType the aggregate type
     * @return A new {@link ProcessingContext} instance containing the given {@code domainEventMessage} aggregate data
     * as resources.
     */
    public static ProcessingContext forMessage(EventMessage domainEventMessage,
                                               String aggregateId,
                                               long seqNo,
                                               String aggregateType) {
        return Message.addToContext(new StubProcessingContext(), domainEventMessage)
                      .withResource(LegacyResources.AGGREGATE_IDENTIFIER_KEY, aggregateId)
                      .withResource(LegacyResources.AGGREGATE_SEQUENCE_NUMBER_KEY, seqNo)
                      .withResource(LegacyResources.AGGREGATE_TYPE_KEY, aggregateType);
    }

    /**
     * Creates a new stub {@link ProcessingContext} for the given {@link Message}. You can use this to create a context
     * compatible with most of the framework. Do note that this context does not commit or advance phases on its own,
     * but you can use {@link #moveToPhase(Phase)} to advance the context to a specific phase.
     *
     * @param message The message to create a context for.
     * @return A new {@link ProcessingContext} instance containing the given {@code message} as a resource.
     */
    public static ProcessingContext forMessage(Message message) {
        return Message.addToContext(new StubProcessingContext(), message);
    }

    /**
     * Creates a new {@link StubProcessingContext} instance that contains a copy of this context's resources and the
     * resources from the provided {@link Context}. The returned instance uses this instance's
     * {@link ApplicationContext}.
     *
     * @param context the Context whose resources should be copied
     * @return a new StubProcessingContext containing the merged resources
     */
    public StubProcessingContext withResources(@NonNull Context context) {
        Objects.requireNonNull(context, "The context may not be null");
        StubProcessingContext copy = new StubProcessingContext(this.applicationContext);
        // Copy existing resources from this stub
        copy.resources.putAll(this.resources);
        // Copy resources from the provided Context (may override existing keys)
        copy.resources.putAll(context.resources());
        return copy;
    }

    /**
     * Creates a new stub {@link ProcessingContext} by copying all resources from the provided {@link Context}.
     *
     * @param context the Context whose resources should be copied into the returned StubProcessingContext
     * @return a new StubProcessingContext containing the same resources as {@code context}
     */
    public static StubProcessingContext fromContext(@NonNull Context context) {
        Objects.requireNonNull(context, "The context may not be null");
        // Delegate to the instance helper to construct a new StubProcessingContext with the provided resources
        return new StubProcessingContext().withResources(context);
    }

    /**
     * Creates a new stub {@link ProcessingContext} with the given {@code component}. You can use this to create a
     * context compatible with most of the framework. Do note that this context does not commit or advance phases on its
     * own, but you can use {@link #moveToPhase(Phase)} to advance the context to a specific phase.
     *
     * @param type     The type of the component to register.
     * @param instance The instance of the component to register.
     * @param <C>      The type of the component to register.
     * @return A new {@link ProcessingContext} instance containing the given {@code component} as a resource.
     */
    public static <C> StubProcessingContext withComponent(@NonNull Class<C> type, @NonNull C instance) {
        return withComponent(ComponentDefinition.ofType(type).withInstance(instance));
    }

    /**
     * Creates a new stub {@link ProcessingContext} with the given {@code componentDefinition}. You can use this to
     * create a context compatible with most of the framework. Do note that this context does not commit or advance
     * phases on its own, but you can use {@link #moveToPhase(Phase)} to advance the context to a specific phase.
     *
     * @param definition The component definition to register.
     * @param <C>        The type of the component to register.
     * @return A new {@link ProcessingContext} instance containing the given {@code componentDefinition} as a resource.
     */
    public static <C> StubProcessingContext withComponent(@NonNull ComponentDefinition<C> definition) {
        return withComponents(componentRegistry -> componentRegistry.registerComponent(definition));
    }

    /**
     * Creates a new stub {@link ProcessingContext} with the given {@code componentRegistrar}. You can use this to
     * create a context compatible with most of the framework. Do note that this context does not commit or advance
     * phases on its own, but you can use {@link #moveToPhase(Phase)} to advance the context to a specific phase.
     *
     * @param componentRegistrar The consumer that registers components in the component registry.
     * @return A new {@link ProcessingContext} instance containing the registered components as resources.
     */
    public static StubProcessingContext withComponents(@NonNull Consumer<ComponentRegistry> componentRegistrar) {
        DefaultComponentRegistry componentRegistry = new DefaultComponentRegistry();
        componentRegistrar.accept(componentRegistry);
        Configuration configuration = componentRegistry.build(new StubLifecycleRegistry());
        ApplicationContext applicationContext = new ConfigurationApplicationContext(configuration);
        return new StubProcessingContext(applicationContext);
    }

    /**
     * Add given {@code message} to the {@link ProcessingContext}. You can use this to create a context compatible with
     * most of the framework. Do note that this context does not commit or advance phases on its own, but you can use
     * {@link #moveToPhase(Phase)} to advance the context to a specific phase.
     *
     * @param message The message to add to the context.
     * @return A new {@link ProcessingContext} instance containing the given {@code message} as a resource.
     */
    public ProcessingContext withMessage(Message message) {
        return Message.addToContext(this, message);
    }

    @NonNull
    @Override
    public <C> C component(@NonNull Class<C> type, @Nullable String name) {
        return applicationContext.component(type, name);
    }
}


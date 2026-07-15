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

import org.axonframework.messaging.core.ApplicationContext;
import org.axonframework.messaging.core.Context;
import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Implementation of the {@link ProcessingLifecycle} adding <b>mutable</b> resource management operations by
 * implementing {@link Context}.
 * <p>
 * It is recommended to construct a {@link ResourceKey} instance when adding/updating/removing resources from the
 * {@link ProcessingContext} to allow cross-referral by sharing the key or personalization when the resource should be
 * private to a specific service.
 *
 * @author Allard Buijze
 * @author Gerard Klijs
 * @author Milan Savić
 * @author Mitchell Herrijgers
 * @author Sara Pellegrini
 * @author Steven van Beelen
 * @since 5.0.0
 */
public interface ProcessingContext extends ProcessingLifecycle, ApplicationContext, Context {

    /**
     * Constructs a new {@link ProcessingContext}, branching off from {@code this} {@code ProcessingContext}.
     * <p>
     * The given {@code resource} as added to the branched {@code ProcessingContext} under the given {@code key}.
     *
     * @param key      The key under which to register the {@code resource} in the branched {@link ProcessingContext}.
     * @param resource The resource to register in the branched {@link ProcessingContext}.
     * @param <T>      The type of resource associated with the {@code key}.
     * @return A new {@link ProcessingContext}, branched off from {@code this} {@code ProcessingContext}.
     */
    @Override
    default <T> ProcessingContext withResource(ResourceKey<T> key,
                                               T resource) {
        return new ResourceOverridingProcessingContext<>(this, key, resource);
    }

    /**
     * Register the given {@code resource} under the given {@code key}.
     *
     * @param key      The key under which to register the {@code resource}.
     * @param resource The resource to register.
     * @param <T>      The type of {@code resource} to register under given @code.
     * @return The previously registered {@code resource}, or {@code null} if none was present.
     */
    <T> T putResource(ResourceKey<T> key,
                      T resource);

    /**
     * Update the resource with given {@code key} using the given {@code resourceUpdater} to describe the update. If no
     * resource is registered with the given {@code key}, the {@code resourceUpdater} is invoked with {@code null}.
     * Otherwise, the function is called with the currently registered resource under that key.
     * <p>
     * The resource is replaced with the return value of the function, or removed when the function returns
     * {@code null}.
     * <p>
     * If the function throws an exception, the exception is rethrown to the caller.
     *
     * @param key             The key to update the resource for.
     * @param resourceUpdater The function performing the update itself.
     * @param <T>             The type of resource to update.
     * @return The new value associated with the {@code key}, or {@code null} when removed.
     */
    <T> T updateResource(ResourceKey<T> key,
                         UnaryOperator<@Nullable T> resourceUpdater);

    /**
     * Register the given {@code instance} under the given {@code key} if no value is currently present.
     *
     * @param key      The key under which to register the resource.
     * @param resource The resource to register when nothing is present for the given {@code key}.
     * @param <T>      The type of {@code resource} to register under given {@code key}.
     * @return The resource previously associated with given {@code key}.
     */
    <T> T putResourceIfAbsent(ResourceKey<T> key,
                              T resource);

    /**
     * If no resource is present for the given {@code key}, the given {@code resourceSupplier} is used to supply the
     * instance to register under this {@code key}.
     * <p>
     * The {@code resourceSupplier} MUST NOT call {@link #computeResourceIfAbsent(ResourceKey, Supplier)} or
     * {@link #putResourceIfAbsent(ResourceKey, Object)} on this {@code ProcessingContext}. The backing resource store
     * rejects re-entrant structural modification and will throw {@link IllegalStateException} (surfacing as a
     * "Recursive update"). This matters when stacking decorators that each cache their wrapped instance per
     * {@code ProcessingContext}: resolve the dependency on the delegate <em>before</em> entering the supplier, rather
     * than from within it.
     * <p>
     * <b>Warning:</b> never use this method for a resource whose construction closes over (holds a reference to)
     * this {@code ProcessingContext} itself - construct a fresh instance directly on every call instead - unless
     * {@code key} is guaranteed to be one of the resources every possible branch of this context overrides. A
     * "branch" here is any {@code ProcessingContext} returned by {@link #withResource(ResourceKey, Object)}: a
     * {@link ResourceOverridingProcessingContext} that overrides one specific resource key on top of a shared
     * parent. Such a branch only intercepts {@code computeResourceIfAbsent} for its own overridden key; every other
     * key falls through to the shared parent, ultimately the root context. If the supplied instance holds onto
     * {@code context}, and {@code context} may be one of several sibling branches of a shared parent (for example,
     * one branch per event in a streaming processor's batch), the first branch to call this method gets its
     * instance cached on the shared root, and every sibling branch that calls afterward receives that <em>same</em>
     * stale instance back - silently operating against the wrong branch. For example, this is unsafe:
     * <pre>{@code
     * // UNSAFE: MyContextAwareGateway's constructor stores a reference to "context".
     * static MyContextAwareGateway forContext(ProcessingContext context) {
     *     return context.computeResourceIfAbsent(RESOURCE_KEY, () -> new MyContextAwareGateway(context));
     * }
     * }</pre>
     * If {@code context} is a per-event branch of a batch, the second event to call {@code forContext} receives the
     * first event's gateway back, closed over the first event's branch. Supply a fresh instance directly instead,
     * bypassing this resource store entirely:
     * <pre>{@code
     * // SAFE: always supplies a fresh instance bound to whichever context is passed in.
     * static MyContextAwareGateway forContext(ProcessingContext context) {
     *     return new MyContextAwareGateway(context);
     * }
     * }</pre>
     * See {@link org.axonframework.messaging.commandhandling.gateway.CommandDispatcher#forContext(ProcessingContext)},
     * {@link org.axonframework.messaging.eventhandling.gateway.EventAppender#forContext(ProcessingContext)}, and
     * {@link org.axonframework.messaging.queryhandling.QueryUpdateEmitter#forContext(ProcessingContext)}, all of
     * which were fixed for exactly this reason.
     * <p>
     * This method remains the correct choice when the cached value does <em>not</em> reference {@code context} and
     * is genuinely meant to be shared for the whole processing session, regardless of how many branches exist. For
     * example:
     * <pre>{@code
     * // SAFE: the cached ConcurrentHashMap never references "context", and is meant to be
     * // shared across every branch of the same processing session.
     * var managedEntities = context.computeResourceIfAbsent(managedEntitiesKey, ConcurrentHashMap::new);
     * }</pre>
     *
     * @param key              The key to register the resource for.
     * @param resourceSupplier The function to supply the resource to register. Must not call back into the resource
     *                         store of this {@code ProcessingContext}.
     * @param <T>              The type of resource registered under given {@code key}.
     * @return The resource associated with the {@code key}.
     */
    <T> T computeResourceIfAbsent(ResourceKey<T> key,
                                  Supplier<T> resourceSupplier);

    /**
     * Removes the resource registered under given {@code key}.
     *
     * @param key The key to remove the registered resource for.
     * @param <T> The type of resource associated with the {@code key}.
     * @return The value previously associated with the {@code key}.
     */
    <T> T removeResource(ResourceKey<T> key);

    /**
     * Remove the resource associated with given {@code key} if the given {@code expectedResource} is the currently
     * associated value.
     *
     * @param key              The key to remove the registered resource for.
     * @param expectedResource The expected resource to remove.
     * @param <T>              The type of resource associated with the {@code key}.
     * @return {@code true} if the resource has been removed, otherwise {@code false}.
     */
    <T> boolean removeResource(ResourceKey<T> key,
                               T expectedResource);
}

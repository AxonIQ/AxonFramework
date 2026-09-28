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

package org.axonframework.messaging.core;

import org.axonframework.messaging.core.unitofwork.ProcessingContext;

/**
 * A {@link ProcessingContext}-scoped replacement for the Axon Framework 4 {@code Scope.describeCurrentScope()},
 * which resolved the currently active {@code Scope} through a {@code ThreadLocal} stack.
 * <p>
 * A {@link ScopeDescriptor} describing whatever {@code Scope} is currently being handled (for example, a Saga
 * instance handling an event) is registered under {@link #RESOURCE_KEY} on the {@link ProcessingContext} active for
 * that handling, the same way {@code SagaLifecycle} registers itself. Unlike {@code SagaLifecycle.forContext(...)},
 * {@link #describeCurrentScope(ProcessingContext)} never throws when nothing is registered: it falls back to
 * {@link NoScopeDescriptor#INSTANCE}, matching the Axon Framework 4 behaviour of
 * {@code Scope.describeCurrentScope()} catching an {@link IllegalStateException} for the very same case.
 *
 * @author Jakob Hatzl
 * @since 5.4.0
 */
public final class CurrentScope {

    /**
     * The {@link Context.ResourceKey} under which the {@link ScopeDescriptor} of the {@code Scope} currently being
     * handled is registered on the {@link ProcessingContext}.
     */
    public static final Context.ResourceKey<ScopeDescriptor> RESOURCE_KEY =
            Context.ResourceKey.withLabel("currentScopeDescriptor");

    private CurrentScope() {
        // Utility class
    }

    /**
     * Describes the {@code Scope} currently being handled within the given {@code context}.
     *
     * @param context the {@link ProcessingContext} to retrieve the current {@link ScopeDescriptor} for
     * @return the {@link ScopeDescriptor} registered for the given {@code context}, or
     * {@link NoScopeDescriptor#INSTANCE} if none is registered
     */
    public static ScopeDescriptor describeCurrentScope(ProcessingContext context) {
        ScopeDescriptor descriptor = context.getResource(RESOURCE_KEY);
        return descriptor != null ? descriptor : NoScopeDescriptor.INSTANCE;
    }
}

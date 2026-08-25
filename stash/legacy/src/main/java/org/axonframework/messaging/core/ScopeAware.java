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
 * Interface describing components which are {@link Scope} aware. Provides functionality to check if a given component
 * can resolve a scope through a given {@link ScopeDescriptor}. If this holds, that component should be able to send a
 * {@link Message} to that Scope.
 *
 * @author Steven van Beelen
 * @since 3.3
 */
public interface ScopeAware {

    /**
     * Send a {@link Message} to a {@link Scope} which is described by the given {@code scopeDescription}.
     *
     * @param message          The {@link Message} to be sent to a {@link Scope}.
     * @param context          The {@link ProcessingContext} in which the {@code message} is being processed.
     * @param scopeDescription A {@code D} extending {@link ScopeDescriptor}, describing the {@link Scope} to send the
     *                         given {@code message} to.
     * @throws Exception if sending the {@code message} failed. Might occur if the message handling process throws an
     *                   exception.
     */
    void send(Message message, ProcessingContext context, ScopeDescriptor scopeDescription) throws Exception;

    /**
     * Check whether this implementation can resolve a {@link Scope} object based on the provided
     * {@code scopeDescription}. Will return {@code true} in case it should be able to resolve the Scope and
     * {@code false} if it cannot.
     *
     * @param scopeDescription a {@link ScopeDescriptor} describing the {@link Scope} to be resolved
     * @return {@code true} in case it should be able to resolve the Scope and {@code false} if it cannot
     */
    boolean canResolve(ScopeDescriptor scopeDescription);
}

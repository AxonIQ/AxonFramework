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

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.Optional;

/**
 * A {@link Scope} that also carries the {@link ProcessingContext} of the invocation it is started for.
 * <p>
 * Axon Framework 4 had two pieces of ambient state while a handler ran: the current {@link Scope} and the current
 * unit of work. Code reached from that handler, such as a {@code DeadlineManager}, used the first to learn which
 * Saga it was called from and the second to defer its work to the unit of work's prepare-commit phase. Axon
 * Framework 5 hands the {@link ProcessingContext} to the handler explicitly and keeps no ambient unit of work. A
 * {@code ContextAwareScope} brings both back for exactly the duration of one synchronous handler invocation, so
 * that such code keeps working without a {@code ProcessingContext} parameter of its own.
 * <p>
 * Marked {@link Internal} because it only exists to bridge Axon Framework 4 APIs in this module. Axon Framework 5
 * components receive their {@link ProcessingContext} as a parameter and should never look it up through a
 * {@code Scope}.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Internal
public abstract class ContextAwareScope extends Scope {

    /**
     * Returns the {@link ProcessingContext} of the invocation this scope was started for.
     *
     * @return the {@link ProcessingContext} of the invocation this scope was started for
     */
    public abstract ProcessingContext processingContext();

    /**
     * Returns the {@link ProcessingContext} carried by the {@link Scope#getCurrentScope() current scope}.
     * <p>
     * Unlike {@link Scope#getCurrentScope()}, this never throws: it returns an empty {@link Optional} when no scope
     * is active, and also when the current scope is not a {@code ContextAwareScope}.
     *
     * @return the {@link ProcessingContext} of the current scope, or an empty {@link Optional} if there is none
     */
    public static Optional<ProcessingContext> currentProcessingContext() {
        Scope current;
        try {
            current = Scope.getCurrentScope();
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
        return current instanceof ContextAwareScope contextAware
                ? Optional.of(contextAware.processingContext())
                : Optional.empty();
    }
}

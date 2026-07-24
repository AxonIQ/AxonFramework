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

package org.axonframework.messaging.core.annotation;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.MessageStream;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * {@link HandlerDefinition} decorator that applies one additional {@link HandlerEnhancerDefinition} to every
 * {@link MessageHandlingMember} produced by its delegate.
 * <p>
 * The delegate's own definition and enhancer composition is left untouched: this decorator wraps the <em>produced</em>
 * member rather than merging enhancer chains, so a delegate of any shape (a classpath-scanned definition, a
 * user-supplied component, or another {@code EnhancingHandlerDefinition}) keeps its behavior and only gains the extra
 * enhancement as the outermost wrapper. Stacking several of these decorators applies their enhancers inside-out: the
 * last-applied decorator enhances outermost.
 * <p>
 * This class is {@link Internal} because it is a wiring detail of
 * {@link org.axonframework.common.configuration.ConfigurationEnhancer ConfigurationEnhancers} that conditionally
 * append an enhancer to the {@code HandlerDefinition} component (for example the tracing enhancers, which are only
 * registered when a {@code SpanFactory} is configured). Applications registering their own enhancers should declare
 * them where the {@code HandlerDefinition} component is assembled instead of instantiating this type.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public final class EnhancingHandlerDefinition implements HandlerDefinition {

    private final HandlerDefinition delegate;
    private final HandlerEnhancerDefinition enhancer;

    /**
     * Initializes a definition producing the {@code delegate}'s members wrapped by the given {@code enhancer}.
     *
     * @param delegate the definition creating the original handling members
     * @param enhancer the enhancer applied to every member the {@code delegate} produces
     */
    public EnhancingHandlerDefinition(HandlerDefinition delegate, HandlerEnhancerDefinition enhancer) {
        this.delegate = requireNonNull(delegate, "The delegate HandlerDefinition may not be null.");
        this.enhancer = requireNonNull(enhancer, "The HandlerEnhancerDefinition may not be null.");
    }

    @Override
    public <T> Optional<MessageHandlingMember<T>> createHandler(
            Class<T> declaringType,
            Method method,
            ParameterResolverFactory parameterResolverFactory,
            Function<Object, MessageStream<?>> messageStreamResolver
    ) {
        return delegate.createHandler(declaringType, method, parameterResolverFactory, messageStreamResolver)
                       .map(enhancer::wrapHandler);
    }
}

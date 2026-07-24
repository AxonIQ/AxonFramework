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

package org.axonframework.messaging.tracing.configuration;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.ConfigurationEnhancer;

/**
 * Ordering constants shared by the tracing modules' {@link ConfigurationEnhancer ConfigurationEnhancers} and
 * decorators.
 * <p>
 * This holder is {@link Internal}: it exists to keep the tracing modules' relative ordering in one place and is
 * expected to be superseded by a framework-wide decorator-order constant holder. Applications ordering their own
 * decorators relative to tracing should not depend on these values.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public final class TracingConfigurationOrder {

    /**
     * Decorator order for all {@code Tracing*} component wrappers.
     * <p>
     * Tracing decorators register at near-maximal order so they are applied <em>last</em> and end up as the
     * <em>outermost</em> wrapper of every decorated component. This is a deliberate convention with two effects:
     * (1) tracing spans cover the work of all inner decorators (interception, retries, security), so a span measures
     * what the caller actually experiences; (2) any tracing wrapper is reliably the outermost layer of a
     * registry-built component, so an {@code instanceof Tracing*} check is sufficient to detect an already-traced
     * component (see {@code TracingStateManager#register}). Custom decorators on the same component types should use
     * a lower order unless they deliberately need to observe tracing itself. The same value is used by every
     * {@code *TracingConfigurationEnhancer} in the tracing modules.
     * <p>
     * This sits on the opposite end of the scale from AxonFramework's own decorators, which deliberately decorate
     * <em>innermost</em> -- e.g.
     * {@code org.axonframework.messaging.commandhandling.interception.InterceptingCommandBus#DECORATION_ORDER}
     * ({@code Integer.MIN_VALUE + 100}). The resulting stack is {@code Tracing(...(Intercepting(Simple...)))}: dispatch
     * and handler spans include interception time, and a message rejected by a dispatch interceptor still produces
     * a span.
     */
    public static final int TRACING_DECORATOR_ORDER = Integer.MAX_VALUE - 1000;

    /**
     * {@link ConfigurationEnhancer#order() Enhancer order} for the {@code *SpanAttributesProviderConfigurationEnhancer}
     * classes contributing the built-in {@link org.axonframework.messaging.tracing.SpanAttributesProvider}
     * implementations.
     * <p>
     * These run at the default order, before the {@link #TRACING_DEFAULTS_ENHANCER_ORDER defaults enhancers}.
     * Correctness does not depend on this: contribution is decorator-based and only applied when the
     * {@link org.axonframework.messaging.tracing.SpanFactory} consuming the registry is constructed -- after the
     * enhance phase has completed.
     */
    public static final int PROVIDER_ENHANCER_ORDER = 0;

    /**
     * {@link ConfigurationEnhancer#order() Enhancer order} for tracing defaults enhancers -- the ones registering
     * default components via {@link org.axonframework.common.configuration.ComponentRegistry#registerIfNotPresent}
     * (the {@link SpanAttributesProviderRegistry}, the {@code *TracingSettings} defaults).
     * <p>
     * Runs last, mirroring AxonFramework's {@code MessagingConfigurationDefaults#ENHANCER_ORDER}, so that
     * user-supplied and property-translated registrations (typically contributed at the default order) take
     * precedence over these defaults.
     */
    public static final int TRACING_DEFAULTS_ENHANCER_ORDER = Integer.MAX_VALUE;

    /**
     * Decorator order on the {@link org.axonframework.messaging.core.annotation.HandlerDefinition} component for the
     * event-tags handler enhancer contributed by {@code axon-eventsourcing}.
     * <p>
     * Deliberately below {@link #METHOD_SPAN_HANDLER_ENHANCER_ORDER}: the tag-enriching wrapper must sit
     * <em>inside</em> the method-span wrapper, so that at invocation time the method span is already active and the
     * resolved event tags land on it (falling back to the event processor's handler span when method spans are
     * suppressed).
     */
    public static final int EVENT_TAG_HANDLER_ENHANCER_ORDER = TRACING_DECORATOR_ORDER;

    /**
     * Decorator order on the {@link org.axonframework.messaging.core.annotation.HandlerDefinition} component for the
     * per-method handler-span enhancer contributed by {@code axon-messaging}.
     * <p>
     * Deliberately above {@link #EVENT_TAG_HANDLER_ENHANCER_ORDER} so the method-span wrapper is applied last and ends
     * up <em>outermost</em>: the span it opens covers every inner handler enhancement, matching the outermost-tracing
     * convention of {@link #TRACING_DECORATOR_ORDER}.
     */
    public static final int METHOD_SPAN_HANDLER_ENHANCER_ORDER = TRACING_DECORATOR_ORDER + 1;

    private TracingConfigurationOrder() {
        // Constants class
    }
}

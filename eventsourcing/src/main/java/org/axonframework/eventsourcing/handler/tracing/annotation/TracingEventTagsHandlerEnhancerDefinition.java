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

package org.axonframework.eventsourcing.handler.tracing.annotation;

import org.axonframework.messaging.tracing.SpanScope;
import org.axonframework.eventsourcing.tracing.configuration.EventSourcingTracingSettings;
import org.axonframework.eventsourcing.eventstore.tracing.attributes.EventTagsSpanAttributesProvider;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.ComponentNotFoundException;
import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandlingMember;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.jspecify.annotations.Nullable;

/**
 * {@link HandlerEnhancerDefinition} that enriches the active tracing span with an event's
 * {@link org.axonframework.messaging.eventstreaming.Tag Tags} when an {@code @EventHandler} is invoked. Tags are
 * resolved through the configured {@link TagResolver} against the payload <em>converted to the handler's declared
 * type</em> - the point in the processing pipeline where the concrete payload class (and thus its {@code @EventTag}
 * members) is known. This complements {@link EventTagsSpanAttributesProvider}, which covers the publish side where the
 * payload is already concrete: streamed events reach the processing side in serialized form (Axon Server does not
 * return the stored tags on the streaming API), so span-creation-time resolution finds nothing there.
 * <p>
 * Attributes are added under the same {@link EventTagsSpanAttributesProvider#EVENT_TAG_PREFIX} keys to the span active
 * on the {@link ProcessingContext} at handler-invocation time - the event processor's handler span, or the method-level
 * handler span when that one is already active (span nesting is last-writer-wins). Payload conversion goes through
 * {@link EventMessage#withConvertedPayload(java.lang.reflect.Type, org.axonframework.conversion.Converter)}, which is
 * cached on the message, so the subsequent parameter resolution for the handler reuses the converted payload instead of
 * converting twice.
 * <p>
 * <b>Best-effort.</b> Tags are resolved from the <em>current</em> payload-class declaration, not from what was
 * physically stored alongside the event; a resolved value can be absent or differ from the stored tag after the payload
 * class evolved.
 * <p>
 * The enrichment is a no-op when no span is active, when no {@link TagResolver} or {@link EventConverter} component is
 * configured, or when {@link EventSourcingTracingSettings#eventTagsEnabled()} is {@code false}.
 * {@code @EventSourcingHandler} members are never wrapped: they fire once per event during entity replay (a hot path),
 * and their enclosing span (the repository load) would collect misleading, mutually-overwriting tag attributes from
 * every replayed event.
 * <p>
 * Discovered via the standard {@code META-INF/services} {@link HandlerEnhancerDefinition}
 * {@link java.util.ServiceLoader} entry, so dropping {@code axon-eventsourcing} on the classpath is enough.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public final class TracingEventTagsHandlerEnhancerDefinition implements HandlerEnhancerDefinition {

    /**
     * Handler-attributes key identifying an {@code @EventSourcingHandler}-annotated member
     * ({@code <AnnotationSimpleName>.<attribute>} as produced by the annotation pipeline). Same detection approach as
     * {@code TracingHandlerEnhancerDefinition} in {@code axon-messaging}.
     */
    private static final String EVENT_SOURCING_HANDLER_ATTRIBUTE = "EventSourcingHandler.payloadType";

    @Override
    public <T> MessageHandlingMember<T> wrapHandler(MessageHandlingMember<T> original) {
        if (!original.canHandleMessageType(EventMessage.class)
                || original.attribute(EVENT_SOURCING_HANDLER_ATTRIBUTE).isPresent()) {
            return original;
        }
        return new EventTagsHandlingMember<>(original);
    }

    /**
     * Wrapper around an event-handling member that resolves the event's tags onto the active span before delegating.
     * Implements {@link EventHandlingMember} so it survives the hard cast the annotation-based event handling component
     * performs.
     */
    private static final class EventTagsHandlingMember<T> extends WrappedMessageHandlingMember<T>
            implements EventHandlingMember<T> {

        private final MessageHandlingMember<T> delegate;

        private EventTagsHandlingMember(MessageHandlingMember<T> delegate) {
            super(delegate);
            this.delegate = delegate;
        }

        @Override
        public MessageStream<?> handle(Message message, ProcessingContext context, @Nullable T target) {
            if (message instanceof EventMessage event) {
                enrichActiveSpan(event, context);
            }
            return super.handle(message, context, target);
        }

        private void enrichActiveSpan(EventMessage event, ProcessingContext context) {
            SpanScope scope = SpanScope.fromContext(context);
            if (scope == null || !settings(context).eventTagsEnabled()) {
                return;
            }
            TagResolver tagResolver = componentOrNull(context, TagResolver.class);
            EventConverter converter = componentOrNull(context, EventConverter.class);
            if (tagResolver == null || converter == null) {
                // Enrichment is documented as a no-op when no TagResolver or EventConverter is configured.
                return;
            }

            // Convert to the handler's declared payload type; the result is cached on the message, so the handler's
            // subsequent parameter resolution reuses it instead of converting again.
            EventMessage resolvable = event.withConvertedPayload(delegate.payloadType(), converter);
            tagResolver.resolve(resolvable).forEach(
                    tag -> scope.span().addAttribute(EventTagsSpanAttributesProvider.EVENT_TAG_PREFIX + tag.key(),
                                                     tag.value()));
        }

        private static EventSourcingTracingSettings settings(ProcessingContext context) {
            EventSourcingTracingSettings settings = componentOrNull(context, EventSourcingTracingSettings.class);
            // No settings registered -- event-tag enrichment stays enabled (the all-enabled default).
            return settings != null ? settings : EventSourcingTracingSettings.enabledByDefault();
        }

        private static <C> @Nullable C componentOrNull(ProcessingContext context, Class<C> type) {
            try {
                return context.component(type);
            } catch (ComponentNotFoundException | UnsupportedOperationException e) {
                // Absent component, or a context without an application context (e.g. tests) -- not configured.
                return null;
            }
        }

        @Override
        public String eventName() {
            return delegate.unwrap(EventHandlingMember.class)
                           .map(EventHandlingMember::eventName)
                           .orElse("");
        }
    }
}

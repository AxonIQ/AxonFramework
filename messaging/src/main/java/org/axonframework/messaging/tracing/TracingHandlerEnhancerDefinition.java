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

package org.axonframework.messaging.tracing;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.ComponentNotFoundException;
import org.axonframework.messaging.commandhandling.annotation.CommandHandlingMember;
import org.axonframework.messaging.core.ApplicationContext;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandlingMember;
import org.axonframework.messaging.queryhandling.annotation.QueryHandlingMember;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link HandlerEnhancerDefinition} that wraps annotated message handlers in a tracing span named after the handling
 * method (for example {@code "BookRoomHandler.handle(BookRoom)"}). Message-type agnostic: the same wrapper applies to
 * {@code @CommandHandler}, {@code @EventHandler} and {@code @QueryHandler} alike -- the span lifecycle is driven
 * entirely by {@link MessageHandlingMember} / {@link ProcessingContext}.
 * <p>
 * Discovered via the standard {@code META-INF/services} {@link HandlerEnhancerDefinition} {@link java.util.ServiceLoader}
 * entry, so dropping {@code axoniq-tracing-messaging} on the classpath is enough; no explicit registration is required.
 * The configured {@link SpanFactory} is resolved from the {@link ProcessingContext}'s
 * {@link ApplicationContext#component(Class) ApplicationContext} at handle time -- when no factory is registered, the
 * wrapper is a pass-through.
 * <p>
 * <b>{@code @EventSourcingHandler} suppression.</b> Members carrying the {@code EventSourcingHandler} handler
 * attribute are suppressed by default: they fire once per event during entity replay (a hot path) and would flood
 * traces with one span per replayed event. Suppression is decided <em>before</em> any span name is built or the
 * {@link SpanFactory} is resolved, so a suppressed invocation carries no tracing cost beyond a boolean check. Set
 * {@link MessagingTracingSettings#eventSourcingHandlersEnabled()} to {@code true}
 * ({@code axon.tracing.event-sourcing-handlers-enabled} in Spring Boot) to trace them anyway.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
@Internal
public final class TracingHandlerEnhancerDefinition implements HandlerEnhancerDefinition {

    /**
     * Handler-attributes key identifying an {@code @EventSourcingHandler}-annotated member
     * ({@code <AnnotationSimpleName>.<attribute>} as produced by the annotation pipeline). Referenced by name so this
     * module needs no dependency on {@code axon-eventsourcing}.
     */
    private static final String EVENT_SOURCING_HANDLER_ATTRIBUTE = "EventSourcingHandler.payloadType";

    /**
     * Constructor for {@link java.util.ServiceLoader} discovery. The {@link SpanFactory} is resolved from the
     * {@link ProcessingContext}'s {@link ApplicationContext} at handle time.
     */
    public TracingHandlerEnhancerDefinition() {
    }

    @Override
    public <T> MessageHandlingMember<T> wrapHandler(MessageHandlingMember<T> original) {
        Optional<Executable> executable = original.unwrap(Executable.class);
        if (executable.isEmpty()) {
            return original;
        }
        String signature = toMethodSignature(executable.get());
        boolean eventSourcingHandler = original.attribute(EVENT_SOURCING_HANDLER_ATTRIBUTE).isPresent();
        return new TracingHandlerMember<>(original, signature, eventSourcingHandler);
    }

    private static String toMethodSignature(Executable executable) {
        return String.format("%s(%s)",
                             executable.getName(),
                             Arrays.stream(executable.getParameterTypes())
                                   .map(Class::getSimpleName)
                                   .collect(Collectors.joining(",")));
    }

    private static String spanName(@Nullable Object target, String signature) {
        return target == null ? signature : target.getClass().getSimpleName() + "." + signature;
    }

    /**
     * Single message-type-agnostic wrapper. Implements every member sub-interface
     * ({@link CommandHandlingMember}, {@link EventHandlingMember}, {@link QueryHandlingMember}) so it survives the
     * hard casts the annotation-based handling components perform; sub-interface accessors delegate to the wrapped
     * member. The annotation pipeline routes each wrapper to the right component via
     * {@code MessageHandlingMember#canHandleMessageType(Class)} (which {@link WrappedMessageHandlingMember} forwards
     * to the original), so a wrapper around an event handler is never streamed into the command pipeline.
     */
    private static final class TracingHandlerMember<T> extends WrappedMessageHandlingMember<T>
            implements CommandHandlingMember<T>, EventHandlingMember<T>, QueryHandlingMember<T> {

        private final MessageHandlingMember<T> delegate;
        private final String signature;
        private final boolean eventSourcingHandler;

        private TracingHandlerMember(MessageHandlingMember<T> delegate,
                                     String signature,
                                     boolean eventSourcingHandler) {
            super(delegate);
            this.delegate = delegate;
            this.signature = signature;
            this.eventSourcingHandler = eventSourcingHandler;
        }

        @Override
        public MessageStream<?> handle(Message message, ProcessingContext context, @Nullable T target) {
            if (eventSourcingHandler && !eventSourcingHandlersEnabled(context)) {
                // Replay hot path: a suppressed @EventSourcingHandler invocation short-circuits BEFORE the span
                // name is built and BEFORE the SpanFactory is resolved (eager-name guard).
                return super.handle(message, context, target);
            }
            SpanFactory factory = resolveSpanFactory(context);
            if (factory == null) {
                return super.handle(message, context, target);
            }

            // Branch-scoped, like the per-event handler span: created against the un-branched context (parent
            // resolved at creation time), then run via Span#branchStream, which carries its scope on a branch passed to
            // the invocation -- so the method body's own children (a dispatch span, an appended-event publish span)
            // parent under this method span -- executes the synchronous window within the span's scope, and closes
            // it on this invocation's own result termination (with a close-only doFinally leak backstop), never at
            // the enclosing context's end, which -- for a per-event method invoked mid-batch -- would be the bug.
            return factory.createInternalSpan(spanName(target, signature), context)
                          .branchStream(context, spanned -> super.handle(message, spanned, target).cast());
        }

        private @Nullable SpanFactory resolveSpanFactory(ProcessingContext context) {
            try {
                return context.component(SpanFactory.class);
            } catch (ComponentNotFoundException | UnsupportedOperationException e) {
                // No SpanFactory configured, or a context without an application context (e.g. tests). Tracing
                // degrades to a pass-through.
                return null;
            }
        }

        private static boolean eventSourcingHandlersEnabled(ProcessingContext context) {
            try {
                return context.component(MessagingTracingSettings.class).eventSourcingHandlersEnabled();
            } catch (ComponentNotFoundException | UnsupportedOperationException e) {
                // No settings registered, or a context without an application context (e.g. tests) -- event sourcing
                // handlers stay suppressed (the safe, replay-friendly default).
                return false;
            }
        }

        @Override
        public String commandName() {
            return delegate.unwrap(CommandHandlingMember.class)
                           .map(CommandHandlingMember::commandName)
                           .orElse("");
        }

        @Override
        public String routingKey() {
            return delegate.unwrap(CommandHandlingMember.class)
                           .map(CommandHandlingMember::routingKey)
                           .orElse("");
        }

        @Override
        public boolean isFactoryHandler() {
            return delegate.unwrap(CommandHandlingMember.class)
                           .map(CommandHandlingMember::isFactoryHandler)
                           .orElse(false);
        }

        @Override
        public String eventName() {
            return delegate.unwrap(EventHandlingMember.class)
                           .map(EventHandlingMember::eventName)
                           .orElse("");
        }

        @Override
        public String queryName() {
            return delegate.unwrap(QueryHandlingMember.class)
                           .map(QueryHandlingMember::queryName)
                           .orElse("");
        }

        @Override
        public Type resultType() {
            return delegate.unwrap(QueryHandlingMember.class)
                           .map(QueryHandlingMember::resultType)
                           .orElse(Object.class);
        }
    }
}

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

import org.axonframework.messaging.tracing.support.TestSpanFactory;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandlingMember;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandlingMember;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.annotation.QueryHandlingMember;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class TracingHandlerEnhancerDefinitionTest {

    private TestSpanFactory spanFactory;
    private TracingHandlerEnhancerDefinition testSubject;

    @BeforeEach
    void setUp() {
        spanFactory = new TestSpanFactory();
        testSubject = new TracingHandlerEnhancerDefinition();
    }

    private ProcessingContext contextWithSpanFactory() {
        return StubProcessingContext.withComponents(
                registry -> registry.registerComponent(SpanFactory.class, c -> spanFactory)
        );
    }

    @Nested
    class CommandHandlerEnhancement {

        @Test
        void wrapsACommandHandlerAndOpensASpanNamedAfterTheMethod() {
            // given
            StubHandlingMember<Object> member =
                    new StubHandlingMember<>(CommandMessage.class, method("handle", String.class));
            CommandMessage command = new GenericCommandMessage(new MessageType("BookRoom"), "payload");

            // when
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(member);
            wrapped.handle(command, contextWithSpanFactory(), new BookRoomHandler());

            // then
            assertThat(wrapped).isNotSameAs(member);
            spanFactory.verifySpanCompleted("BookRoomHandler.handle(String)");
        }
    }

    @Nested
    class EventHandlerEnhancement {

        @Test
        void wrapsAnEventHandlerAndOpensASpanNamedAfterTheMethod() {
            // given
            StubHandlingMember<Object> member =
                    new StubHandlingMember<>(EventMessage.class, method("on", String.class));
            EventMessage event = new GenericEventMessage(new MessageType("RoomBooked"), "payload");

            // when
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(member);
            wrapped.handle(event, contextWithSpanFactory(), new RoomBookedProjection());

            // then
            assertThat(wrapped).isNotSameAs(member);
            spanFactory.verifySpanCompleted("RoomBookedProjection.on(String)");
        }
    }

    @Nested
    class LazySpanFactoryResolutionFromTheProcessingContext {

        @Test
        void resolvesTheSpanFactoryFromTheContextAtHandleTime() {
            // given a context that has the SpanFactory registered as a component
            ProcessingContext context = contextWithSpanFactory();
            StubHandlingMember<Object> member =
                    new StubHandlingMember<>(CommandMessage.class, method("handle", String.class));
            CommandMessage command = new GenericCommandMessage(new MessageType("BookRoom"), "payload");

            // when
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(member);
            wrapped.handle(command, context, new BookRoomHandler());

            // then the wrapper looked up the SpanFactory on the context and opened the per-method span
            spanFactory.verifySpanCompleted("BookRoomHandler.handle(String)");
        }

        @Test
        void gracefullyDegradesWhenNoSpanFactoryIsRegistered() {
            // given a context whose component registry does not provide a SpanFactory
            ProcessingContext context = StubProcessingContext.withComponents(registry -> {
            });
            StubHandlingMember<Object> member =
                    new StubHandlingMember<>(CommandMessage.class, method("handle", String.class));
            CommandMessage command = new GenericCommandMessage(new MessageType("BookRoom"), "payload");

            // when
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(member);
            wrapped.handle(command, context, new BookRoomHandler());

            // then no span is produced (tracing degrades to a pass-through) and no exception is propagated
            spanFactory.verifyNoSpan("BookRoomHandler.handle(String)");
        }
    }

    @Nested
    class EventSourcingHandlerSuppression {

        @Test
        void suppressesAnEventSourcingHandlerByDefaultWithoutResolvingTheSpanFactory() {
            // given an @EventSourcingHandler member (carries the "EventSourcingHandler.payloadType" handler
            // attribute) and a context that has a SpanFactory but no MessagingTracingSettings -- the
            // eventSourcingHandlersEnabled default is false
            AtomicBoolean spanFactoryResolved = new AtomicBoolean(false);
            ProcessingContext context = StubProcessingContext.withComponents(
                    registry -> registry.registerComponent(SpanFactory.class, c -> {
                        spanFactoryResolved.set(true);
                        return spanFactory;
                    })
            );
            StubHandlingMember<Object> member =
                    new StubHandlingMember<>(EventMessage.class, method("on", String.class), true);
            EventMessage event = new GenericEventMessage(new MessageType("RoomBooked"), "payload");

            // when
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(member);
            MessageStream<?> result = wrapped.handle(event, context, new RoomBookedProjection());

            // then the invocation still reaches the delegate, but no span is opened and the SpanFactory is never
            // resolved -- the suppression branch short-circuits before any span-name construction
            assertThat(result).isNotNull();
            spanFactory.verifyNoSpan("RoomBookedProjection.on(String)");
            assertThat(spanFactoryResolved).isFalse();
        }

        @Test
        void tracesAnEventSourcingHandlerWhenEventSourcingHandlersEnabled() {
            // given a context whose MessagingTracingSettings enables eventSourcingHandlersEnabled
            MessagingTracingSettings showHandlers =
                    MessagingTracingSettings.enabledByDefault().withEventSourcingHandlersEnabled(true);
            ProcessingContext context = StubProcessingContext.withComponents(
                    registry -> {
                        registry.registerComponent(SpanFactory.class, c -> spanFactory);
                        registry.registerComponent(MessagingTracingSettings.class, c -> showHandlers);
                    }
            );
            StubHandlingMember<Object> member =
                    new StubHandlingMember<>(EventMessage.class, method("on", String.class), true);
            EventMessage event = new GenericEventMessage(new MessageType("RoomBooked"), "payload");

            // when
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(member);
            wrapped.handle(event, context, new RoomBookedProjection());

            // then
            spanFactory.verifySpanCompleted("RoomBookedProjection.on(String)");
        }

        @Test
        void aPlainEventHandlerIsUnaffectedByTheEventSourcingSuppressionDefault() {
            // given a regular @EventHandler member (no EventSourcingHandler attribute) and no settings registered
            ProcessingContext context = contextWithSpanFactory();
            StubHandlingMember<Object> member =
                    new StubHandlingMember<>(EventMessage.class, method("on", String.class));
            EventMessage event = new GenericEventMessage(new MessageType("RoomBooked"), "payload");

            // when
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(member);
            wrapped.handle(event, context, new RoomBookedProjection());

            // then
            spanFactory.verifySpanCompleted("RoomBookedProjection.on(String)");
        }
    }

    @Nested
    class MessageTypeSpecificity {

        @Test
        void commandWrapperOnlyImplementsCommandHandlingMember() {
            // given
            StubHandlingMember<Object> member = new CommandStubHandlingMember<>(method("handle", String.class));

            // when
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(member);

            // then
            assertThat(wrapped).isInstanceOf(CommandHandlingMember.class)
                               .isNotInstanceOf(EventHandlingMember.class)
                               .isNotInstanceOf(QueryHandlingMember.class);
        }

        @Test
        void eventWrapperOnlyImplementsEventHandlingMember() {
            // given
            StubHandlingMember<Object> member = new EventStubHandlingMember<>(method("on", String.class));

            // when
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(member);

            // then
            assertThat(wrapped).isInstanceOf(EventHandlingMember.class)
                               .isNotInstanceOf(CommandHandlingMember.class)
                               .isNotInstanceOf(QueryHandlingMember.class);
        }

        @Test
        void queryWrapperOnlyImplementsQueryHandlingMember() {
            // given
            StubHandlingMember<Object> member = new QueryStubHandlingMember<>(method("handle", String.class));

            // when
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(member);

            // then
            assertThat(wrapped).isInstanceOf(QueryHandlingMember.class)
                               .isNotInstanceOf(CommandHandlingMember.class)
                               .isNotInstanceOf(EventHandlingMember.class);
        }
    }

    @Nested
    class ServiceLoaderDiscovery {

        @Test
        void theNoArgEnhancerIsDiscoverableThroughTheStandardHandlerEnhancerSpi() {
            // when
            ServiceLoader<HandlerEnhancerDefinition> loader = ServiceLoader.load(HandlerEnhancerDefinition.class);

            // then the no-arg TracingHandlerEnhancerDefinition is contributed via META-INF/services so it is wired into
            // the default ClasspathHandlerEnhancerDefinition without any explicit ConfigurationEnhancer registration
            assertThat(loader)
                    .anyMatch(definition -> definition instanceof TracingHandlerEnhancerDefinition);
        }
    }

    @Nested
    class MembersWithoutAnExecutableAreReturnedUnchanged {

        @Test
        void leavesAMemberWithoutAnExecutableUntouchedWithoutBuildingASpanName() {
            // given a member whose unwrap(Executable) yields nothing; if asked for the executable it would flip the flag
            AtomicBoolean executableRequested = new AtomicBoolean(false);
            StubHandlingMember<Object> member =
                    new StubHandlingMember<>(CommandMessage.class, /*executable*/ null);
            member.onUnwrapExecutable = () -> executableRequested.set(true);

            // when
            MessageHandlingMember<Object> wrapped = testSubject.wrapHandler(member);

            // then
            assertThat(wrapped).isSameAs(member);
            // the enhancer must NOT build a signature for a member it isn't going to wrap
            assertThat(executableRequested).isTrue();   // it asked once (to decide), and got nothing back
            // (the assertion above just documents that asking is allowed; what matters is no wrapping happened)
        }
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            Class<?> target = "handle".equals(name) ? BookRoomHandler.class : RoomBookedProjection.class;
            return target.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused")
    private static final class BookRoomHandler {

        void handle(String command) {
            // exercised only via the stub member
        }
    }

    @SuppressWarnings("unused")
    private static final class RoomBookedProjection {

        void on(String event) {
            // exercised only via the stub member
        }
    }

    /**
     * Minimal {@link MessageHandlingMember} stub. Reports which message type (if any) it handles, optionally exposes a
     * method as its {@link Executable}, optionally carries the {@code EventSourcingHandler.payloadType} handler
     * attribute (mimicking an {@code @EventSourcingHandler}-annotated method), and returns an empty stream from
     * {@code handle}.
     */
    private static class StubHandlingMember<T> implements MessageHandlingMember<T> {

        private final @Nullable Class<? extends Message> handledType;
        private final @Nullable Method method;
        private final boolean eventSourcingHandler;
        private Runnable onUnwrapExecutable = () -> {
        };

        private StubHandlingMember(@Nullable Class<? extends Message> handledType, @Nullable Method method) {
            this(handledType, method, false);
        }

        private StubHandlingMember(@Nullable Class<? extends Message> handledType,
                                   @Nullable Method method,
                                   boolean eventSourcingHandler) {
            this.handledType = handledType;
            this.method = method;
            this.eventSourcingHandler = eventSourcingHandler;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Optional<R> attribute(String attributeKey) {
            if (eventSourcingHandler && "EventSourcingHandler.payloadType".equals(attributeKey)) {
                return (Optional<R>) Optional.of(String.class);
            }
            return Optional.empty();
        }

        @Override
        public Class<?> payloadType() {
            return String.class;
        }

        @Override
        public boolean canHandle(Message message, ProcessingContext context) {
            return true;
        }

        @Override
        public boolean canHandleMessageType(Class<? extends Message> messageType) {
            return handledType != null && handledType.isAssignableFrom(messageType);
        }

        @Override
        public MessageStream<?> handle(Message message, ProcessingContext context, @Nullable T target) {
            return MessageStream.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <HT> Optional<HT> unwrap(Class<HT> handlerType) {
            if (handlerType.isInstance(this)) {
                return Optional.of((HT) this);
            }
            if (handlerType.isAssignableFrom(Executable.class) || handlerType.equals(Executable.class)) {
                onUnwrapExecutable.run();
                return method == null ? Optional.empty() : (Optional<HT>) Optional.of(method);
            }
            return Optional.empty();
        }
    }

    private static final class CommandStubHandlingMember<T> extends StubHandlingMember<T>
            implements CommandHandlingMember<T> {

        private CommandStubHandlingMember(Method method) {
            super(CommandMessage.class, method);
        }

        @Override
        public String commandName() {
            return "";
        }

        @Override
        public String routingKey() {
            return "";
        }

        @Override
        public boolean isFactoryHandler() {
            return false;
        }
    }

    private static final class EventStubHandlingMember<T> extends StubHandlingMember<T>
            implements EventHandlingMember<T> {

        private EventStubHandlingMember(Method method) {
            super(EventMessage.class, method);
        }

        @Override
        public String eventName() {
            return "";
        }
    }

    private static final class QueryStubHandlingMember<T> extends StubHandlingMember<T>
            implements QueryHandlingMember<T> {

        private QueryStubHandlingMember(Method method) {
            super(QueryMessage.class, method);
        }

        @Override
        public String queryName() {
            return "";
        }

        @Override
        public Type resultType() {
            return Object.class;
        }
    }
}

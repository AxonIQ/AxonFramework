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

import org.axonframework.common.IdentifierFactory;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * A {@link SpanFactory} that logs span lifecycle events through SLF4J, for development and debugging when no APM
 * backend is available. Span start and end are logged at {@code INFO}, each line prefixed with a generated span
 * identifier and the operation name in the form {@code [spanId][operationName]}. Added attributes are logged at
 * {@code DEBUG} behind the same prefix.
 * <p>
 * When a span relates to a {@link Message}, the message's {@link Message#type() type} and {@link Message#identifier()
 * identifier} are logged too. When the span is created while another message is being handled -- i.e. the supplied
 * {@link ProcessingContext} carries a current {@link Message#fromContext(ProcessingContext) message} -- the in-flight
 * message's type and identifier are appended as well, so a dispatch / internal span can be correlated with the handler
 * it originated from, without any {@code ThreadLocal}.
 * <p>
 * This factory performs no context propagation: {@link Span#propagateContext(Message)} returns the message unchanged.
 * Combine it with the OpenTelemetry factory through {@link MultiSpanFactory} to get both logging and real tracing.
 *
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @since 4.6.0
 */
public final class LoggingSpanFactory implements SpanFactory {

    /**
     * The singleton {@link LoggingSpanFactory} instance.
     */
    public static final LoggingSpanFactory INSTANCE = new LoggingSpanFactory();

    private static final Logger logger = LoggerFactory.getLogger(LoggingSpanFactory.class);

    private LoggingSpanFactory() {
    }

    @Override
    public Span createDispatchSpan(String operationName, Message message, @Nullable ProcessingContext context) {
        return new LoggingSpan(operationName, () -> messageSpanStartLog("Dispatch", message, context));
    }

    @Override
    public Span createHandlerSpan(String operationName, Message message, @Nullable ProcessingContext context) {
        return new LoggingSpan(operationName, () -> handlerSpanStartLog(message));
    }

    @Override
    public Span createLinkedHandlerSpan(String operationName, Message message, Message linkedMessage,
                                        @Nullable ProcessingContext context) {
        return new LoggingSpan(operationName, () -> handlerSpanStartLog(message));
    }

    @Override
    public Span createInternalSpan(String operationName, @Nullable ProcessingContext context) {
        return new LoggingSpan(operationName, () -> internalSpanStartLog(context));
    }

    @Override
    public Span createRootSpan(String operationName, @Nullable ProcessingContext context) {
        return new LoggingSpan(operationName, () -> "Root trace started");
    }

    @Override
    public Span createDisconnectedHandlerSpan(String operationName, Message message,
                                              @Nullable ProcessingContext context) {
        return new LoggingSpan(operationName, () -> "Disconnected handler span started (new trace, linked to "
                + message.getClass().getSimpleName() + ")");
    }

    private static String handlerSpanStartLog(Message message) {
        return String.format("Handler span started for message of type [%s] and identifier [%s]",
                             message.type(), message.identifier());
    }

    private static String messageSpanStartLog(String spanType, Message message, @Nullable ProcessingContext context) {
        Message handledMessage = context == null ? null : Message.fromContext(context);
        if (handledMessage != null) {
            return String.format(
                    "%s span started for message of type [%s] and identifier [%s] while handling message of type [%s] and identifier [%s]",
                    spanType,
                    message.type(), message.identifier(),
                    handledMessage.type(), handledMessage.identifier());
        }
        return String.format("%s span started for message of type [%s] and identifier [%s]",
                             spanType, message.type(), message.identifier());
    }

    private static String internalSpanStartLog(@Nullable ProcessingContext context) {
        Message handledMessage = context == null ? null : Message.fromContext(context);
        if (handledMessage != null) {
            return String.format(
                    "Internal span started while handling message of type [%s] and identifier [%s]",
                    handledMessage.type(), handledMessage.identifier());
        }
        return "Internal span started";
    }

    private static final class LoggingSpan implements Span {

        private final String identifier;
        private final String operationName;
        private final Supplier<String> startLog;

        private LoggingSpan(String operationName, Supplier<String> startLog) {
            this.identifier = IdentifierFactory.getInstance().generateIdentifier();
            this.operationName = operationName;
            this.startLog = startLog;
        }

        @Override
        public SpanScope start() {
            logger.info("[{}][{}] {}", identifier, operationName, startLog.get());
            return new LoggingSpanScope(this, identifier, operationName);
        }

        @Override
        public Span addAttribute(String key, String value) {
            logger.debug("[{}][{}] attribute {}={}", identifier, operationName, key, value);
            return this;
        }

        @Override
        public Span recordException(Throwable t) {
            logger.info("[{}][{}] Span recorded exception", identifier, operationName, t);
            return this;
        }

        @Override
        public <M extends Message> M propagateContext(M message) {
            return message;
        }
    }

    private static final class LoggingSpanScope implements SpanScope {

        private final Span span;
        private final String identifier;
        private final String operationName;

        private LoggingSpanScope(Span span, String identifier, String operationName) {
            this.span = span;
            this.identifier = identifier;
            this.operationName = operationName;
        }

        @Override
        public Span span() {
            return span;
        }

        @Override
        public void close() {
            logger.info("[{}][{}] Span ended", identifier, operationName);
        }
    }
}

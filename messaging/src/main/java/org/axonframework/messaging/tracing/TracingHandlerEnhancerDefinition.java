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

import org.axonframework.common.BuilderUtils;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Enhances message handlers with the provided {@link SpanFactory}, wrapping handling of the message in a {@link Span}
 * that is reported to the monitoring tooling.
 * <p>
 * Since {@code EventSourcingHandlers} can be very noisy when loading the aggregate, they can be enabled or disabled
 * separately through constructor configuration.
 *
 * @author Mitchell Herrijgers
 * @since 4.6.0
 */
public class TracingHandlerEnhancerDefinition implements HandlerEnhancerDefinition {

    private final SpanFactory spanFactory;
    private final boolean showEventSourcingHandlers;

    /**
     * Creates a new {@link TracingHandlerEnhancerDefinition} based on the builder.
     *
     * @param builder The builder to construct the {@link TracingHandlerEnhancerDefinition} from.
     */
    protected TracingHandlerEnhancerDefinition(Builder builder) {
        BuilderUtils.assertNonNull(builder.spanFactory, "SpanFactory must be provided!");
        this.spanFactory = builder.spanFactory;
        this.showEventSourcingHandlers = builder.showEventSourcingHandlers;
    }

    /**
     * Instantiate a builder to create a {@link TracingHandlerEnhancerDefinition}.
     * <p>
     * The {@code showEventSourcingHandlers} is defaulted to {@code false}. The {@link SpanFactory} is a hard
     * requirement and should be provided.
     *
     * @return The builder to create a {@link TracingHandlerEnhancerDefinition}.
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public <T> MessageHandlingMember<T> wrapHandler(MessageHandlingMember<T> original) {
        if (!showEventSourcingHandlers && isEventSourcingHandler(original)) {
            return original;
        }

        Optional<Executable> unwrap = original.unwrap(Executable.class);
        if (unwrap.isEmpty()) {
            return original;
        }
        String signature = toMethodSignature(unwrap.get());
        return new WrappedMessageHandlingMember<>(original) {
            @Override
            public MessageStream<?> handle(Message message, ProcessingContext context, T target) {
                return spanFactory.createInternalSpan(() -> getSpanName(target, signature))
                                  .runSupplier(() -> super.handle(message, context, target));
            }
        };
    }

    private static <T> String getSpanName(T target, String signature) {
        return target == null ? signature : target.getClass().getSimpleName() + "." + signature;
    }

    private boolean isEventSourcingHandler(MessageHandlingMember<?> original) {
        return original.attribute("EventSourcingHandler.payloadType").isPresent();
    }

    private String toMethodSignature(Executable executable) {
        return String.format("%s(%s)",
                             executable.getName(),
                             Arrays.stream(executable.getParameterTypes()).map(Class::getSimpleName)
                                   .collect(Collectors.joining(",")));
    }

    /**
     * Builder class to instantiate a {@link TracingHandlerEnhancerDefinition}.
     * <p>
     * The {@code showEventSourcingHandlers} is defaulted to {@code false}. The {@link SpanFactory} is a hard
     * requirement and should be provided.
     */
    public static class Builder {

        private SpanFactory spanFactory;
        private boolean showEventSourcingHandlers = false;


        /**
         * Configures the {@link SpanFactory} the handler enhancer should use for tracing.
         *
         * @param spanFactory The {@link SpanFactory} to configure.
         * @return The builder, for fluent interfacing.
         */
        public Builder spanFactory(SpanFactory spanFactory) {
            BuilderUtils.assertNonNull(spanFactory, "SpanFactory can not be set to null!");
            this.spanFactory = spanFactory;
            return this;
        }

        /**
         * Configures whether event sourcing handlers should be traced. Defaults to {@code false}.
         *
         * @param showEventSourcingHandlers Whether event sourcing handlers should be traced.
         * @return The builder, for fluent interfacing.
         */
        public Builder showEventSourcingHandlers(boolean showEventSourcingHandlers) {
            this.showEventSourcingHandlers = showEventSourcingHandlers;
            return this;
        }

        /**
         * Initializes the {@link TracingHandlerEnhancerDefinition} based on the builder contents.
         *
         * @return The {@link TracingHandlerEnhancerDefinition}.
         */
        public TracingHandlerEnhancerDefinition build() {
            return new TracingHandlerEnhancerDefinition(this);
        }
    }
}

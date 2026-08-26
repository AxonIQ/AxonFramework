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

package org.axonframework.modelling.saga;

import org.axonframework.common.BuilderUtils;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanFactory;

/**
 * Default implementation of the {@link SagaManagerSpanFactory}. The attribute used for the saga identifier can be
 * configured.
 *
 * @author Mitchell Herrijgers
 * @since 4.9.0
 */
public class DefaultSagaManagerSpanFactory implements SagaManagerSpanFactory {

    private final SpanFactory spanFactory;
    private final String sagaIdentifierAttribute;

    /**
     * Creates a new {@link DefaultSagaManagerSpanFactory} using the provided {@code builder}.
     *
     * @param builder The builder to build the {@link DefaultSagaManagerSpanFactory} from.
     */
    protected DefaultSagaManagerSpanFactory(Builder builder) {
        builder.validate();
        this.spanFactory = builder.builderSpanFactory;
        this.sagaIdentifierAttribute = builder.builderSagaIdentifierAttribute;
    }


    /**
     * Creates a new {@link Builder} to build a {@link DefaultSagaManagerSpanFactory} with. The default values are:
     * <ul>
     *     <li>{@code sagaIdentifierAttribute} defaults to {@code axon.sagaIdentifier}</li>
     * </ul>
     * The {@code spanFactory} is a required field and should be provided.
     *
     * @return The {@link Builder} to build a {@link DefaultSagaManagerSpanFactory} with.
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Span createCreateSagaInstanceSpan(EventMessage event, Class<?> sagaType, String sagaIdentifier) {
        return spanFactory.createInternalSpan("SagaManager.createSaga(" + sagaType.getSimpleName() + ")", null)
                          .addAttribute(sagaIdentifierAttribute, sagaIdentifier);
    }

    @Override
    public Span createInvokeSagaSpan(EventMessage event, Class<?> sagaType, Saga<?> saga) {
        return spanFactory.createInternalSpan("SagaManager.invokeSaga(" + sagaType.getSimpleName() + ")", null)
                          .addAttribute(sagaIdentifierAttribute, saga.getSagaIdentifier());
    }


    /**
     * Builder class to instantiate a {@link DefaultSagaManagerSpanFactory}. The default values are:
     * <ul>
     *     <li>{@code sagaIdentifierAttribute} defaults to {@code axon.sagaIdentifier}</li>
     * </ul>
     * The {@code spanFactory} is a required field and should be provided.
     */
    public static class Builder {

        private SpanFactory builderSpanFactory;
        private String builderSagaIdentifierAttribute = "axon.sagaIdentifier";

        /**
         * Sets the {@link SpanFactory} to use to create the spans. This is a required field.
         *
         * @param spanFactory The {@link SpanFactory} to use to create the spans.
         * @return The current Builder instance, for fluent interfacing.
         */
        public Builder spanFactory(SpanFactory spanFactory) {
            BuilderUtils.assertNonNull(spanFactory, "spanFactory may not be null");
            this.builderSpanFactory = spanFactory;
            return this;
        }

        /**
         * Sets the attribute name to use for the saga identifier. Defaults to {@code axon.sagaIdentifier}.
         *
         * @param sagaIdentifierAttribute The attribute name to use for the saga identifier.
         * @return The current Builder instance, for fluent interfacing.
         */
        public Builder sagaIdentifierAttribute(String sagaIdentifierAttribute) {
            BuilderUtils.assertNonEmpty(sagaIdentifierAttribute, "sagaIdentifierAttribute may not be null or empty");
            this.builderSagaIdentifierAttribute = sagaIdentifierAttribute;
            return this;
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         */
        protected void validate() {
            BuilderUtils.assertNonNull(builderSpanFactory, "spanFactory may not be null");
        }

        /**
         * Initializes a {@link DefaultSagaManagerSpanFactory} as specified through this Builder.
         *
         * @return The {@link DefaultSagaManagerSpanFactory} as specified through this Builder.
         */
        public DefaultSagaManagerSpanFactory build() {
            return new DefaultSagaManagerSpanFactory(this);
        }
    }
}

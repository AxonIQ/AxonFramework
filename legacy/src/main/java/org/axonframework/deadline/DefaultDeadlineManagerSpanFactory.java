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

package org.axonframework.deadline;

import org.axonframework.common.BuilderUtils;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.tracing.Span;
import org.axonframework.messaging.tracing.SpanFactory;

/**
 * Default implementation of the {@link DeadlineManagerSpanFactory}. The attributes used for the id of the deadline and
 * the scope of the deadline are configurable.
 *
 * @author Mitchell Herrijgers
 * @since 4.9.0
 */
public class DefaultDeadlineManagerSpanFactory implements DeadlineManagerSpanFactory {

    private final SpanFactory spanFactory;
    private final String deadlineIdAttribute;
    private final String scopeAttribute;

    /**
     * Creates a new {@link DefaultDeadlineManagerSpanFactory} using the provided {@code builder}. The default values are:
     * <ul>
     *     <li>{@code deadlineIdAttribute} defaults to {@code axon.deadlineId}</li>
     *     <li>{@code scopeAttribute} defaults to {@code axon.deadlineScope}</li>
     * </ul>
     * The {@code spanFactory} is a required field and should be provided.
     *
     * @param builder The builder to build the {@link DefaultDeadlineManagerSpanFactory} from.
     */
    protected DefaultDeadlineManagerSpanFactory(Builder builder) {
        builder.validate();
        this.spanFactory = builder.builderSpanFactory;
        this.deadlineIdAttribute = builder.builderDeadlineIdAttribute;
        this.scopeAttribute = builder.builderScopeAttribute;
    }

    /**
     * Creates a new {@link Builder} to build a {@link DefaultDeadlineManagerSpanFactory} with. The default values are:
     * <ul>
     *     <li>{@code deadlineIdAttribute} defaults to {@code axon.deadlineId}</li>
     *     <li>{@code scopeAttribute} defaults to {@code axon.deadlineScope}</li>
     * </ul>
     * The {@code spanFactory} is a required field and should be provided.
     *
     * @return The {@link Builder} to build a {@link DefaultDeadlineManagerSpanFactory} with.
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Span createScheduleSpan(String deadlineName, String deadlineId, DeadlineMessage deadlineMessage) {
        return spanFactory.createDispatchSpan(
                "DeadlineManager.scheduleDeadline(" + deadlineName + ")",
                deadlineMessage,
                null
        ).addAttribute(deadlineIdAttribute, deadlineId);
    }

    @Override
    public Span createCancelScheduleSpan(String deadlineName, String deadlineId) {
        return spanFactory.createInternalSpan(
                "DeadlineManager.cancelDeadline(" + deadlineName + ")",
                null
        ).addAttribute(deadlineIdAttribute, deadlineId);
    }

    @Override
    public Span createCancelAllSpan(String deadlineName) {
        return spanFactory.createInternalSpan(
                "DeadlineManager.cancelAllDeadlines(" + deadlineName + ")",
                null
        );
    }

    @Override
    public Span createCancelAllWithinScopeSpan(String deadlineName, ScopeDescriptor scopeDescriptor) {
        return spanFactory.createInternalSpan(
                "DeadlineManager.cancelAllWithinScope(" + deadlineName + ")",
                null
        ).addAttribute(scopeAttribute, scopeDescriptor.scopeDescription());
    }

    @Override
    public Span createExecuteSpan(String deadlineName, String deadlineId, DeadlineMessage deadlineMessage) {
        return spanFactory.createDisconnectedHandlerSpan(
                "DeadlineManager.executeDeadline(" + deadlineName + ")",
                deadlineMessage,
                null
        ).addAttribute(deadlineIdAttribute, deadlineId);
    }

    /**
     * Builder class to instantiate a {@link DefaultDeadlineManagerSpanFactory}. The {@code spanFactory} is a required
     * field and should be provided.
     */
    public static class Builder {

        private SpanFactory builderSpanFactory;
        private String builderDeadlineIdAttribute = "axon.deadlineId";
        private String builderScopeAttribute = "axon.deadlineScope";

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
         * Sets the attribute key to use for the deadline id. Defaults to {@code axon.deadlineId}.
         *
         * @param deadlineIdAttribute The attribute key to use for the deadline id.
         * @return The current Builder instance, for fluent interfacing.
         */
        public Builder deadlineIdAttribute(String deadlineIdAttribute) {
            BuilderUtils.assertNonEmpty(deadlineIdAttribute, "deadlineIdAttribute may not be null");
            this.builderDeadlineIdAttribute = deadlineIdAttribute;
            return this;
        }

        /**
         * Sets the attribute key to use for the deadline scope. Defaults to {@code axon.deadlineScope}.
         *
         * @param scopeAttribute The attribute key to use for the deadline scope.
         * @return The current Builder instance, for fluent interfacing.
         */
        public Builder scopeAttribute(String scopeAttribute) {
            BuilderUtils.assertNonEmpty(scopeAttribute, "scopeAttribute may not be null");
            this.builderScopeAttribute = scopeAttribute;
            return this;
        }

        /**
         * Validates whether the fields contained in this Builder are set accordingly.
         */
        protected void validate() {
            BuilderUtils.assertNonEmpty(builderDeadlineIdAttribute, "deadlineIdAttribute may not be null");
            BuilderUtils.assertNonEmpty(builderScopeAttribute, "scopeAttribute may not be null");
            BuilderUtils.assertNonNull(builderSpanFactory, "spanFactory may not be null");
        }

        /**
         * Initializes a {@link DefaultDeadlineManagerSpanFactory} as specified through this Builder.
         *
         * @return The {@link DefaultDeadlineManagerSpanFactory} as specified through this Builder.
         */
        public DefaultDeadlineManagerSpanFactory build() {
            return new DefaultDeadlineManagerSpanFactory(this);
        }
    }
}

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

import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ListenerInvocationErrorHandler;
import org.axonframework.messaging.eventhandling.processing.errorhandling.LoggingErrorHandler;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.tracing.NoOpSpanFactory;
import org.axonframework.modelling.saga.metamodel.AnnotationSagaMetaModelFactory;
import org.axonframework.modelling.saga.metamodel.SagaModel;
import org.axonframework.messaging.tracing.SpanFactory;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.axonframework.common.BuilderUtils.assertNonNull;

/**
 * Implementation of the SagaManager that uses annotations on the Sagas to describe the lifecycle management. This
 * implementation can manage several types of Saga in a single AnnotatedSagaManager.
 *
 * @param <T> The type of Saga managed by this instance
 * @author Allard Buijze
 * @since 0.7
 */
public class AnnotatedSagaManager<T> extends AbstractSagaManager<T> {

    private final SagaModel<T> sagaMetaModel;

    /**
     * Instantiate a {@link AnnotatedSagaManager} based on the fields contained in the {@link Builder}.
     * <p>
     * The {@code sagaFactory} is defaulted to a {@code sagaType.newInstance()} call throwing a
     * {@link SagaInstantiationException} if it fails, the {@link SagaManagerSpanFactory} defaults to a
     * {@link DefaultSagaManagerSpanFactory} backed by a {@link NoOpSpanFactory}, and the
     * {@link ListenerInvocationErrorHandler} is defaulted to a {@link LoggingErrorHandler}. The {@link SagaRepository}
     * and {@code sagaType} are <b>hard requirements</b> and as such should be provided.
     * <p>
     * Will assert that the {@link SagaRepository}, {@code sagaType}, {@code sagaFactory} and
     * {@link ListenerInvocationErrorHandler} are not {@code null}, and will throw an
     * {@link org.axonframework.common.AxonConfigurationException} if any of them is {@code null}. Additionally, the
     * provided Builder's goal is to either build a {@link SagaModel} specifying generic {@code T} as the Saga type to
     * be stored or derive it based on the given {@code sagaType}. All Sagas managed by this Saga manager must be
     * {@code instanceOf} this Saga type.
     *
     * @param builder the {@link Builder} used to instantiate a {@link AnnotatedSagaManager} instance
     */
    protected AnnotatedSagaManager(Builder<T> builder) {
        super(builder);
        this.sagaMetaModel = builder.buildSagaModel();
    }

    /**
     * Instantiate a Builder to be able to create a {@link AnnotatedSagaManager}.
     * <p>
     * The {@code sagaFactory} is defaulted to a {@code sagaType.newInstance()} call throwing a
     * {@link SagaInstantiationException} if it fails, the {@link ListenerInvocationErrorHandler} is defaulted to a
     * {@link LoggingErrorHandler} and the {@link SagaManagerSpanFactory} is defaulted to a
     * {@link DefaultSagaManagerSpanFactory} backed by a {@link NoOpSpanFactory}.
     * <p>
     * This Builder either allows directly setting a {@link SagaModel} of generic type {@code T}, or it will generate
     * one based of the required {@code sagaType} field of type {@link Class}. Thus, either the SagaModel <b>or</b> the
     * {@code sagaType} should be provided. All Sagas managed by this instances must be {@code instanceOf} this saga
     * type. Additionally, the {@link SagaRepository} and {@code sagaType} are <b>hard requirements</b> and as such
     * should be provided.
     *
     * @param <T> a generic specifying the Saga type managed by this {@link AbstractSagaManager} implementation
     * @return a Builder to be able to create a {@link AnnotatedSagaManager}
     */
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }


    @Override
    public boolean canHandle(EventMessage eventMessage, ProcessingContext context, Segment segment) {
        // The segment is used to filter Saga instances, so all events match when there's a handler
        return sagaMetaModel.hasHandlerMethod(eventMessage, context);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected SagaInitializationPolicy getSagaCreationPolicy(EventMessage event, ProcessingContext context) {
        return sagaMetaModel.findHandlerMethods(event, context).stream()
                            .map(h -> h.unwrap(SagaMethodMessageHandlingMember.class).orElse(null))
                            .filter(Objects::nonNull)
                            .filter(sh -> sh.getCreationPolicy() != SagaCreationPolicy.NONE)
                            .map(sh -> new SagaInitializationPolicy(
                                    sh.getCreationPolicy(), sh.getAssociationValue(event)
                            ))
                            .findFirst()
                            .orElse(SagaInitializationPolicy.NONE);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Set<AssociationValue> extractAssociationValues(EventMessage event, ProcessingContext context) {
        return sagaMetaModel.findHandlerMethods(event, context).stream()
                            .map(h -> h.unwrap(SagaMethodMessageHandlingMember.class).orElse(null))
                            .filter(Objects::nonNull)
                            .map(h -> h.getAssociationValue(event))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());
    }

    /**
     * Builder class to instantiate a {@link AnnotatedSagaManager}.
     * <p>
     * The {@code sagaFactory} is defaulted to a {@code sagaType.newInstance()} call throwing a
     * {@link SagaInstantiationException} if it fails, the {@link SpanFactory} defaults to a
     * {@link NoOpSpanFactory}, and the {@link ListenerInvocationErrorHandler} is defaulted to
     * a {@link LoggingErrorHandler}.
     * <p>
     * This Builder either allows directly setting a {@link SagaModel} of generic type {@code T}, or it will generate
     * one based of the required {@code sagaType} field of type {@link Class}. Thus, either the SagaModel <b>or</b> the
     * {@code sagaType} should be provided. All Sagas managed by this instances must be {@code instanceOf} this saga
     * type. Additionally, the {@link SagaRepository} and {@code sagaType} are <b>hard requirements</b> and as such
     * should be provided.
     *
     * @param <T> a generic specifying the Saga type managed by this {@link AbstractSagaManager} implementation
     */
    public static class Builder<T> extends AbstractSagaManager.Builder<T> {

        private ParameterResolverFactory parameterResolverFactory;
        private HandlerDefinition handlerDefinition;
        private SagaModel<T> sagaModel;

        @Override
        public Builder<T> sagaRepository(SagaRepository<T> sagaRepository) {
            super.sagaRepository(sagaRepository);
            return this;
        }

        @Override
        public Builder<T> sagaType(Class<T> sagaType) {
            super.sagaType(sagaType);
            return this;
        }

        @Override
        public Builder<T> sagaFactory(Supplier<T> sagaFactory) {
            super.sagaFactory(sagaFactory);
            return this;
        }

        @Override
        public Builder<T> listenerInvocationErrorHandler(
                ListenerInvocationErrorHandler listenerInvocationErrorHandler) {
            super.listenerInvocationErrorHandler(listenerInvocationErrorHandler);
            return this;
        }

        @Override
        public Builder<T> spanFactory(SagaManagerSpanFactory spanFactory) {
            super.spanFactory(spanFactory);
            return this;
        }

        /**
         * Sets the {@link ParameterResolverFactory} used to resolve parameters for annotated handlers for the given
         * {@code sagaType}. Only used to instantiate a {@link SagaModel} if no SagaModel has been provided directly.
         *
         * @param parameterResolverFactory a {@link ParameterResolverFactory} used to resolve parameters for annotated
         *                                 handlers contained in the Aggregate
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder<T> parameterResolverFactory(ParameterResolverFactory parameterResolverFactory) {
            assertNonNull(parameterResolverFactory, "ParameterResolverFactory may not be null");
            this.parameterResolverFactory = parameterResolverFactory;
            return this;
        }

        /**
         * Sets the {@link HandlerDefinition} used to create concrete handlers for the given {@code sagaType}.
         * Only used to instantiate a {@link SagaModel} if no SagaModel has been provided directly.
         *
         * @param handlerDefinition a {@link HandlerDefinition} used to create concrete handlers for the given
         *                          {@code sagaType}.
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder<T> handlerDefinition(HandlerDefinition handlerDefinition) {
            assertNonNull(handlerDefinition, "HandlerDefinition may not be null");
            this.handlerDefinition = handlerDefinition;
            return this;
        }

        /**
         * Sets the {@link SagaModel} of generic type {@code T}, describing the structure of the Saga this
         * {@link AbstractSagaManager} implementation will manage. If this is not provided directly, the
         * {@code sagaType} will be used to instantiate a SagaModel.
         *
         * @param sagaModel the {@link SagaModel} of generic type {@code T} of the Saga this {@link AbstractSagaManager}
         *                  implementation will manage
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder<T> sagaModel(SagaModel<T> sagaModel) {
            assertNonNull(sagaModel, "SagaModel may not be null");
            this.sagaModel = sagaModel;
            return this;
        }

        /**
         * Initializes a {@link AnnotatedSagaManager} as specified through this Builder.
         *
         * @return a {@link AnnotatedSagaManager} as specified through this Builder
         */
        public AnnotatedSagaManager<T> build() {
            return new AnnotatedSagaManager<>(this);
        }

        /**
         * Instantiate the {@link SagaModel} of generic type {@code T} describing the structure of the Saga
         * this {@link AbstractSagaManager} implementation will manage.
         *
         * @return a {@link SagaModel} of generic type {@code T} describing the Saga this
         * {@link AbstractSagaManager} implementation will manage
         */
        protected SagaModel<T> buildSagaModel() {
            if (sagaModel == null) {
                return inspectSagaModel();
            } else {
                return sagaModel;
            }
        }

        @SuppressWarnings("Duplicates")
        private SagaModel<T> inspectSagaModel() {
            if (parameterResolverFactory == null && handlerDefinition == null) {
                return new AnnotationSagaMetaModelFactory().modelOf(sagaType);
            } else if (parameterResolverFactory != null && handlerDefinition == null) {
                return new AnnotationSagaMetaModelFactory(parameterResolverFactory).modelOf(sagaType);
            } else {
                return new AnnotationSagaMetaModelFactory(
                        parameterResolverFactory, handlerDefinition
                ).modelOf(sagaType);
            }
        }
    }
}

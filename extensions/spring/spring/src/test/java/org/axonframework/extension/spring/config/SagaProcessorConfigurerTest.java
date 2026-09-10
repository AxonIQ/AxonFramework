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

package org.axonframework.extension.spring.config;

import org.axonframework.common.FutureUtils;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.DuplicateModuleRegistrationException;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating {@link SagaProcessorConfigurer}: the dedicated Saga event processor wiring, built through its
 * own {@link DefaultProcessorModuleFactory} instance, registered entirely separately from the plain event handling
 * beans' modules. Sagas resolving to the same processor name share that processor; a Saga and a regular handler never
 * do.
 *
 * @author Mateusz Nowak
 */
class SagaProcessorConfigurerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final EventProcessorSettings.SubscribingEventProcessorSettings SUBSCRIBING_SETTINGS = () -> null;

    private @Nullable AxonConfiguration configuration;
    private @Nullable GenericApplicationContext applicationContext;
    private @Nullable InMemorySagaStore sagaStore;

    @AfterEach
    void tearDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
        if (applicationContext != null) {
            applicationContext.close();
        }
    }

    @Nested
    class NoSagas {

        @Test
        void doesNotTouchSettingsAtAll() {
            // given an application context that would fail if the configurer looked up settings unconditionally
            GenericApplicationContext context = new GenericApplicationContext();
            context.refresh();

            // when / then
            assertThatCode(() -> enhance(context)).doesNotThrowAnyException();
        }
    }

    @Nested
    class ProcessorNaming {

        @Test
        void namesTheProcessorAfterTheSagaType() {
            // given
            enhance(context(saga("orderSaga", PlainSaga.class)));

            // then
            assertThat(hasProcessor("PlainSagaProcessor")).isTrue();
        }

        @Test
        void losesItsDefaultNameToANamespaceOnTheType() {
            // given a Saga type carrying a Namespace, the Axon Framework 5 successor of @ProcessingGroup
            enhance(context(saga("orderSaga", NamespacedSaga.class)));

            // then
            assertThat(hasProcessor("orders")).isTrue();
            assertThat(hasProcessor("NamespacedSagaProcessor")).isFalse();
        }
    }

    @Nested
    class TwoSagasResolvingToTheSameProcessor {

        @Test
        void shareThatOneProcessor() {
            // given two Sagas whose Namespace resolves to the same processor name, as Axon Framework 4 allowed
            // under a shared @ProcessingGroup
            enhance(context(
                    saga("orderSaga", NamespacedSaga.class),
                    saga("shipmentSaga", AlsoNamespacedSaga.class)
            ));
            assertThat(hasProcessor("orders")).isTrue();

            // when a matching event is published on that one processor
            publish(new OrderPlaced("order-1"));

            // then both Sagas started from it, proving both are handled by the same processor
            AssociationValue orderId = new AssociationValue("orderId", "order-1");
            assertThat(sagaStore.findSagas(NamespacedSaga.class, orderId)).hasSize(1);
            assertThat(sagaStore.findSagas(AlsoNamespacedSaga.class, orderId)).hasSize(1);
        }
    }

    @Nested
    class ASagaAndAPlainHandlerResolvingToTheSameProcessor {

        @Test
        void isRejectedRatherThanSilentlyMerged() {
            // given a Saga resolving to "orders", and a plain (non-Saga) module already claiming that name --
            // built the way DefaultProcessorModuleFactory would, entirely separately from the Saga wiring
            GenericApplicationContext context = context(saga("orderSaga", NamespacedSaga.class));
            SagaProcessorConfigurer configurer = new SagaProcessorConfigurer();
            configurer.setApplicationContext(context);
            applicationContext = context;

            MessagingConfigurer messaging = MessagingConfigurer.create();
            messaging.componentRegistry(cr -> cr.registerComponent(SagaStore.class, c -> new InMemorySagaStore()));
            messaging.componentRegistry(cr -> cr.registerModule(
                    EventProcessorModule.subscribing("orders")
                                        .eventHandlingComponents(phase -> phase.declarative(
                                                "plainHandler",
                                                c -> SimpleEventHandlingComponent.create("plainHandler")
                                        ))
                                        .notCustomized()
            ));

            // then, a Saga is never silently grouped with a regular event handler
            assertThatThrownBy(() -> messaging.componentRegistry(configurer::enhance))
                    .isInstanceOf(DuplicateModuleRegistrationException.class);
        }
    }

    private void enhance(GenericApplicationContext context) {
        applicationContext = context;
        sagaStore = new InMemorySagaStore();
        SagaProcessorConfigurer configurer = new SagaProcessorConfigurer();
        configurer.setApplicationContext(context);
        MessagingConfigurer messaging = MessagingConfigurer.create();
        // The SagaStore Sagas are kept in is an Axon component, resolved from the Configuration, not a Spring bean.
        messaging.componentRegistry(cr -> cr.registerComponent(SagaStore.class, c -> sagaStore));
        messaging.componentRegistry(configurer::enhance);
        configuration = messaging.start();
    }

    private boolean hasProcessor(String processorName) {
        return configuration.getModuleConfiguration("EventProcessor[" + processorName + "]").isPresent();
    }

    private void publish(Object payload) {
        EventMessage event = new GenericEventMessage(new MessageType(payload.getClass()), payload);
        FutureUtils.joinAndUnwrap(
                configuration.getComponent(EventSink.class).publish(null, List.of(event)), TIMEOUT
        );
    }

    private static GenericApplicationContext context(SagaSpec... specs) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(
                EventProcessorSettings.MapWrapper.class,
                () -> new EventProcessorSettings.MapWrapper(Map.of(EventProcessorSettings.DEFAULT, SUBSCRIBING_SETTINGS))
        );
        for (SagaSpec spec : specs) {
            // The Saga's own bean, registered under its own name -- exactly as SpringSagaLookup finds it -- so
            // SpringSagaDescriptor#beanDefinition() resolves against the Saga type, not this descriptor.
            context.registerBean(spec.beanName(), spec.sagaType());
            context.registerBean(
                    spec.beanName() + "$$Registrar",
                    SpringSagaDescriptor.class,
                    () -> new SpringSagaDescriptor(spec.beanName(), spec.sagaType(), context.getBeanFactory())
            );
        }
        context.refresh();
        return context;
    }

    private static SagaSpec saga(String beanName, Class<?> sagaType) {
        return new SagaSpec(beanName, sagaType);
    }

    private record SagaSpec(String beanName, Class<?> sagaType) {

    }

    private static class PlainSaga {

    }

    @Namespace("orders")
    @SuppressWarnings("unused")
    private static class NamespacedSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        void on(OrderPlaced event) {
            // Only its presence matters here.
        }
    }

    @Namespace("orders")
    @SuppressWarnings("unused")
    private static class AlsoNamespacedSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        void on(OrderPlaced event) {
            // Only its presence matters here.
        }
    }

    private record OrderPlaced(String orderId) {

    }
}

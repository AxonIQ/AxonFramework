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

package org.axonframework.config;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.FutureUtils;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.modelling.saga.AbstractSagaManager;
import org.axonframework.modelling.saga.AnnotatedSagaManager;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.SagaRepository;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.modelling.saga.repository.AnnotatedSagaRepository;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SagaConfigurerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final AssociationValue ORDER_1 = new AssociationValue("orderId", "order-1");

    private @Nullable AxonConfiguration configuration;

    @AfterEach
    void tearDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    @Nested
    class DefaultConfiguration {

        @Test
        void buildsAManagerThatCreatesAndStoresSagas() {
            // given
            InMemorySagaStore sagaStore = new InMemorySagaStore();
            startWith(SagaConfigurer.forType(OrderSaga.class), sagaStore);

            // when
            publish(new OrderPlaced("order-1"));

            // then
            assertThat(sagaStore.findSagas(OrderSaga.class, ORDER_1)).hasSize(1);
        }

        @Test
        void reportsWhenNoSagaStoreIsConfigured() {
            // given
            SagaConfigurer<OrderSaga> sagaConfigurer = SagaConfigurer.forType(OrderSaga.class);
            MessagingConfigurer configurer = MessagingConfigurer.create()
                                                                  .eventProcessing(processing -> processing.subscribing(
                                                                          subscribing -> subscribing.defaultProcessor(
                                                                                  "sagas",
                                                                                  components -> components.declarative(
                                                                                          "Saga[OrderSaga]",
                                                                                          sagaConfigurer
                                                                                  )
                                                                          )
                                                                  ));

            // when / then
            assertThatThrownBy(() -> configuration = configurer.start())
                    .rootCause()
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining(SagaStore.class.getName())
                    .hasMessageContaining(OrderSaga.class.getName());
        }
    }

    @Nested
    class CustomConfiguration {

        @Test
        void configuredFactoryCreatesTheSaga() {
            // given
            InMemorySagaStore sagaStore = new InMemorySagaStore();
            Collaborator collaborator = new Collaborator();
            SagaConfigurer<CollaboratingSaga> sagaConfigurer =
                    SagaConfigurer.forType(CollaboratingSaga.class)
                                  .configureSagaFactory(() -> new CollaboratingSaga(collaborator));
            startWith(sagaConfigurer, sagaStore);

            // when
            publish(new OrderPlaced("order-1"));

            // then
            SagaStore.Entry<CollaboratingSaga> entry = sagaStore.loadSaga(
                    CollaboratingSaga.class,
                    sagaStore.findSagas(CollaboratingSaga.class, ORDER_1).iterator().next()
            );
            assertThat(entry).isNotNull();
            assertThat(entry.saga().collaborator).isSameAs(collaborator);
        }

        @Test
        void configuredStoreReplacesTheConfigurationComponent() {
            // given
            InMemorySagaStore registeredStore = new InMemorySagaStore();
            InMemorySagaStore configuredStore = new InMemorySagaStore();
            SagaConfigurer<OrderSaga> sagaConfigurer = SagaConfigurer.forType(OrderSaga.class)
                                                                      .configureSagaStore(c -> configuredStore);
            startWith(sagaConfigurer, registeredStore);

            // when
            publish(new OrderPlaced("order-1"));

            // then
            assertThat(configuredStore.findSagas(OrderSaga.class, ORDER_1)).hasSize(1);
            assertThat(registeredStore.size()).isZero();
        }

        @Test
        void configuredRepositoryBypassesTheConfiguredStore() {
            // given
            InMemorySagaStore repositoryStore = new InMemorySagaStore();
            AtomicInteger storeBuilderInvocations = new AtomicInteger();
            SagaConfigurer<OrderSaga> sagaConfigurer = SagaConfigurer.forType(OrderSaga.class)
                                                                      .configureSagaStore(c -> {
                                                                          storeBuilderInvocations.incrementAndGet();
                                                                          return new InMemorySagaStore();
                                                                      })
                                                                      .configureRepository(
                                                                              c -> repositoryFor(
                                                                                      OrderSaga.class,
                                                                                      repositoryStore,
                                                                                      c
                                                                              )
                                                                      );
            startWith(sagaConfigurer, new InMemorySagaStore());

            // when
            publish(new OrderPlaced("order-1"));

            // then
            assertThat(repositoryStore.findSagas(OrderSaga.class, ORDER_1)).hasSize(1);
            assertThat(storeBuilderInvocations).hasValue(0);
        }

        @Test
        void configuredManagerBypassesRepositoryStoreAndFactory() {
            // given
            InMemorySagaStore managerStore = new InMemorySagaStore();
            AtomicInteger repositoryBuilderInvocations = new AtomicInteger();
            AtomicInteger storeBuilderInvocations = new AtomicInteger();
            AtomicInteger factoryInvocations = new AtomicInteger();
            SagaConfigurer<OrderSaga> sagaConfigurer = SagaConfigurer.forType(OrderSaga.class)
                                                                      .configureSagaStore(c -> {
                                                                          storeBuilderInvocations.incrementAndGet();
                                                                          return new InMemorySagaStore();
                                                                      })
                                                                      .configureRepository(c -> {
                                                                          repositoryBuilderInvocations
                                                                                  .incrementAndGet();
                                                                          return repositoryFor(
                                                                                  OrderSaga.class,
                                                                                  new InMemorySagaStore(),
                                                                                  c
                                                                          );
                                                                      })
                                                                      .configureSagaFactory(() -> {
                                                                          factoryInvocations.incrementAndGet();
                                                                          return new OrderSaga();
                                                                      })
                                                                      .configureSagaManager(
                                                                              c -> managerFor(
                                                                                      OrderSaga.class,
                                                                                      managerStore,
                                                                                      c
                                                                              )
                                                                      );
            startWith(sagaConfigurer, new InMemorySagaStore());

            // when
            publish(new OrderPlaced("order-1"));

            // then
            assertThat(managerStore.findSagas(OrderSaga.class, ORDER_1)).hasSize(1);
            assertThat(repositoryBuilderInvocations).hasValue(0);
            assertThat(storeBuilderInvocations).hasValue(0);
            assertThat(factoryInvocations).hasValue(0);
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void repeatedBuildReturnsTheSameManagerAndFreezesConfiguration() {
            // given
            SagaConfigurer<OrderSaga> sagaConfigurer = SagaConfigurer.forType(OrderSaga.class);
            InMemorySagaStore sagaStore = new InMemorySagaStore();
            configuration = MessagingConfigurer.create()
                                               .componentRegistry(
                                                       registry -> registry.registerComponent(
                                                               SagaStore.class,
                                                               c -> sagaStore
                                                       )
                                               )
                                               .build();

            // when
            AbstractSagaManager<OrderSaga> first = sagaConfigurer.build(configuration);
            AbstractSagaManager<OrderSaga> second = sagaConfigurer.build(MessagingConfigurer.create().build());

            // then
            assertThat(second).isSameAs(first);
            assertThatThrownBy(() -> sagaConfigurer.configureSagaStore(c -> sagaStore))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessage("SagaConfiguration has already been created. Cannot make modifications.");
        }
    }

    @Nested
    class NullChecks {

        @Test
        void rejectsNullConfigurationValues() {
            // given
            SagaConfigurer<Object> sagaConfigurer = SagaConfigurer.forType(Object.class);
            Function<Configuration, AbstractSagaManager<Object>> managerBuilder = null;
            Function<Configuration, SagaRepository<Object>> repositoryBuilder = null;
            Function<Configuration, SagaStore<? super Object>> storeBuilder = null;
            Supplier<Object> sagaFactory = null;

            // when / then
            assertThatThrownBy(() -> SagaConfigurer.forType(null))
                    .isInstanceOf(AxonConfigurationException.class);
            assertThatThrownBy(() -> sagaConfigurer.configureSagaManager(managerBuilder))
                    .isInstanceOf(AxonConfigurationException.class);
            assertThatThrownBy(() -> sagaConfigurer.configureRepository(repositoryBuilder))
                    .isInstanceOf(AxonConfigurationException.class);
            assertThatThrownBy(() -> sagaConfigurer.configureSagaStore(storeBuilder))
                    .isInstanceOf(AxonConfigurationException.class);
            assertThatThrownBy(() -> sagaConfigurer.configureSagaFactory(sagaFactory))
                    .isInstanceOf(AxonConfigurationException.class);
        }
    }

    private <T> void startWith(SagaConfigurer<T> sagaConfigurer, SagaStore<?> sagaStore) {
        configuration = MessagingConfigurer.create()
                                           .componentRegistry(
                                                   registry -> registry.registerComponent(
                                                           SagaStore.class,
                                                           c -> sagaStore
                                                   )
                                           )
                                           .eventProcessing(processing -> processing.subscribing(
                                                   subscribing -> subscribing.defaultProcessor(
                                                           "sagas",
                                                           components -> components.declarative("Saga", sagaConfigurer)
                                                   )
                                           ))
                                           .start();
    }

    private void publish(Object payload) {
        EventMessage event = EventTestUtils.asEventMessage(payload);
        FutureUtils.joinAndUnwrap(
                configuration.getComponent(EventSink.class).publish(null, List.of(event)),
                TIMEOUT
        );
    }

    private static <T> SagaRepository<T> repositoryFor(
            Class<T> sagaType,
            SagaStore<? super T> sagaStore,
            Configuration configuration
    ) {
        AnnotatedSagaRepository.Builder<T> builder = AnnotatedSagaRepository.<T>builder()
                                                                              .sagaType(sagaType)
                                                                              .sagaStore(sagaStore);
        configuration.getOptionalComponent(org.axonframework.messaging.core.annotation.ParameterResolverFactory.class)
                     .ifPresent(builder::parameterResolverFactory);
        configuration.getOptionalComponent(org.axonframework.messaging.core.annotation.HandlerDefinition.class)
                     .ifPresent(builder::handlerDefinition);
        return builder.build();
    }

    private static <T> AbstractSagaManager<T> managerFor(
            Class<T> sagaType,
            SagaStore<? super T> sagaStore,
            Configuration configuration
    ) {
        AnnotatedSagaManager.Builder<T> builder = AnnotatedSagaManager.<T>builder()
                                                                      .sagaType(sagaType)
                                                                      .sagaRepository(
                                                                              repositoryFor(
                                                                                      sagaType,
                                                                                      sagaStore,
                                                                                      configuration
                                                                              )
                                                                      );
        configuration.getOptionalComponent(org.axonframework.messaging.core.annotation.ParameterResolverFactory.class)
                     .ifPresent(builder::parameterResolverFactory);
        configuration.getOptionalComponent(org.axonframework.messaging.core.annotation.HandlerDefinition.class)
                     .ifPresent(builder::handlerDefinition);
        return builder.build();
    }

    record OrderPlaced(String orderId) {

    }

    @SuppressWarnings({"unused", "removal"})
    public static class OrderSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPlaced event) {
            // Starting the saga is the behavior under test.
        }
    }

    private static class Collaborator {

    }

    @SuppressWarnings({"unused", "removal"})
    public static class CollaboratingSaga {

        private final Collaborator collaborator;

        CollaboratingSaga(Collaborator collaborator) {
            this.collaborator = collaborator;
        }

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPlaced event) {
            // Starting the saga is the behavior under test.
        }
    }
}

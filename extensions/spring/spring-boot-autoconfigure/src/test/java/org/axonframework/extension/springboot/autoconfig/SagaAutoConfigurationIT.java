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

package org.axonframework.extension.springboot.autoconfig;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.gateway.EventGateway;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorConfiguration;
import org.axonframework.modelling.saga.AnnotatedSagaManager;
import org.axonframework.modelling.saga.AssociationValue;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.axonframework.spring.stereotype.Saga;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.context.annotation.Primary;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.test.context.ContextConfiguration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Test class validating the Spring Boot support for Axon Framework 4 {@link Saga @Saga} types end to end.
 * <p>
 * Where {@code SpringSagaConfigurerTest} and {@code LegacySagaAutoConfigurationTest} pin the wiring in isolation, the
 * tests here run a full Spring Boot application: a {@code @Saga} bean is discovered, bound to its own event processor,
 * and started by a published event, with the Saga instance ending up in the auto-configured {@link SagaStore}.
 * <p>
 * Every context excludes Hibernate and the embedded {@code DataSource} auto-configuration. This module carries
 * {@code hsqldb} and {@code spring-boot-starter-data-jpa} on its test classpath, so leaving them in would swap the
 * in-memory event store and Saga store for JPA-backed ones and make the assertions depend on a database.
 *
 * @author Mateusz Nowak
 */
class SagaAutoConfigurationIT {

    private static final String SIMPLE_SAGA_PROCESSOR = "EventProcessor[SimpleSagaProcessor]";
    private static final String SHARED_PROCESSOR = "EventProcessor[shared-saga-processor]";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String HISTORIC_EVENT_ID = "historic-event";

    @Nested
    @SpringBootTest(
            classes = {TestContext.class, SimpleSaga.class},
            webEnvironment = SpringBootTest.WebEnvironment.NONE
    )
    class DefaultProcessorTest {

        @Autowired
        private ApplicationContext context;

        @Test
        void aPublishedEventStartsTheSagaOnItsOwnProcessor() {
            // given
            String id = UUID.randomUUID().toString();

            // when
            publish(context, new EchoEvent(id));

            // then - the Saga instance is kept in the store the auto-configuration falls back to
            InMemorySagaStore sagaStore = context.getBean("sagaStore", InMemorySagaStore.class);
            assertThat(context.getBean(SagaStore.class)).isSameAs(sagaStore);
            await().atMost(TIMEOUT)
                   .until(() -> !sagaStore.findSagas(SimpleSaga.class, new AssociationValue("id", id)).isEmpty());

            // then - the Saga is handled by a pooled streaming processor named after the Saga type
            Configuration module = moduleConfiguration(context, SIMPLE_SAGA_PROCESSOR);
            assertThat(module.getOptionalComponent(PooledStreamingEventProcessorConfiguration.class)).isPresent();
        }

        @Test
        void springBeansAreInjectedAsHandlerParameters() {
            // given - the recorder is a plain Spring bean, not injected by a ResourceInjector but by the handler's
            // parameter resolution
            String id = UUID.randomUUID().toString();
            EventRecorder recorder = context.getBean(EventRecorder.class);

            // when
            publish(context, new EchoEvent(id));

            // then
            await().atMost(TIMEOUT).until(() -> recorder.handled("SimpleSaga", id));
        }

        @Test
        void theSagaIsRegisteredOnItsOwnProcessorOnly() {
            // when
            AxonConfiguration configuration = context.getBean(AxonConfiguration.class);

            // then - the Saga is a prototype bean, so no package-derived processor picked it up as a plain handler
            assertThat(configuration.getModuleConfiguration(
                    "EventProcessor[" + SagaAutoConfigurationIT.class.getPackageName() + "]"
            )).isEmpty();
            List<String> sagaComponentNames = sagaComponentNames(configuration, "SimpleSaga");
            assertThat(sagaComponentNames).hasSize(1);
            assertThat(sagaComponentNames.getFirst()).contains("Saga[SimpleSaga]");

            // then - and that single component is the Saga manager, not an annotated handler bean
            Map<String, EventHandlingComponent> components =
                    moduleConfiguration(context, SIMPLE_SAGA_PROCESSOR).getComponents(EventHandlingComponent.class);
            assertThat(components).hasSize(1);
            assertThat(components.values().iterator().next().unwrap(AnnotatedSagaManager.class)).isPresent();
        }
    }

    @Nested
    @SpringBootTest(
            classes = {TestContext.class, HistorySeedingContext.class, SimpleSaga.class},
            webEnvironment = SpringBootTest.WebEnvironment.NONE
    )
    class HistoryTest {

        @Autowired
        private ApplicationContext context;

        @Test
        void eventsPublishedBeforeTheProcessorStartedAreIgnored() {
            // given - HistorySeedingContext appended an event before the event processors started
            String id = UUID.randomUUID().toString();
            EventRecorder recorder = context.getBean(EventRecorder.class);
            assertThat(context.getBean(HistoryPublisher.class).isRunning()).isTrue();

            // when
            publish(context, new EchoEvent(id));

            // then - all segments start at the token the stream had at start-up, so the earlier event is never read
            await().atMost(TIMEOUT).until(() -> recorder.handled("SimpleSaga", id));
            assertThat(recorder.handled()).doesNotContain("SimpleSaga:" + HISTORIC_EVENT_ID);
            assertThat(context.getBean("sagaStore", InMemorySagaStore.class).size()).isEqualTo(1);
        }
    }

    @Nested
    @SpringBootTest(
            classes = {TestContext.class, TwoStoresContext.class, StoredSaga.class},
            webEnvironment = SpringBootTest.WebEnvironment.NONE
    )
    class SagaStoreAttributeTest {

        @Autowired
        private ApplicationContext context;

        @Test
        void theSagaIsKeptInTheNamedStoreBean() {
            // given
            String id = UUID.randomUUID().toString();
            InMemorySagaStore primaryStore = context.getBean("sagaStore", InMemorySagaStore.class);
            InMemorySagaStore secondaryStore = context.getBean("secondaryStore", InMemorySagaStore.class);

            // when
            publish(context, new EchoEvent(id));

            // then
            await().atMost(TIMEOUT)
                   .until(() -> !secondaryStore.findSagas(StoredSaga.class, new AssociationValue("id", id))
                                               .isEmpty());
            assertThat(primaryStore.size()).isZero();
        }
    }

    @Nested
    @SpringBootTest(
            classes = {TestContext.class, SimpleSaga.class},
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = "axon.eventhandling.processors[SimpleSagaProcessor].mode=subscribing"
    )
    class ProcessorPropertiesTest {

        @Autowired
        private ApplicationContext context;

        @Test
        void theSagaProcessorFollowsTheConfiguredMode() {
            // given
            String id = UUID.randomUUID().toString();
            EventRecorder recorder = context.getBean(EventRecorder.class);

            // when
            publish(context, new EchoEvent(id));

            // then
            await().atMost(TIMEOUT).until(() -> recorder.handled("SimpleSaga", id));
            Configuration module = moduleConfiguration(context, SIMPLE_SAGA_PROCESSOR);
            assertThat(module.getOptionalComponent(SubscribingEventProcessorConfiguration.class)).isPresent();
            assertThat(module.getOptionalComponent(PooledStreamingEventProcessorConfiguration.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(
            classes = {TestContext.class, FirstSharedSaga.class, SecondSharedSaga.class},
            webEnvironment = SpringBootTest.WebEnvironment.NONE
    )
    class SharedProcessorTest {

        @Autowired
        private ApplicationContext context;

        @Test
        void sagasSharingADerivedNameShareOneProcessor() {
            // given
            String id = UUID.randomUUID().toString();
            EventRecorder recorder = context.getBean(EventRecorder.class);

            // when
            publish(context, new EchoEvent(id));

            // then - both Sagas are started by the one event, as they are co-located on one processor
            await().atMost(TIMEOUT).until(() -> recorder.handled("FirstSharedSaga", id)
                    && recorder.handled("SecondSharedSaga", id));
            AxonConfiguration configuration = context.getBean(AxonConfiguration.class);
            assertThat(configuration.getModuleConfiguration("EventProcessor[FirstSharedSagaProcessor]")).isEmpty();
            assertThat(configuration.getModuleConfiguration("EventProcessor[SecondSharedSagaProcessor]")).isEmpty();
            assertThat(moduleConfiguration(context, SHARED_PROCESSOR).getComponents(EventHandlingComponent.class))
                    .hasSize(2);
        }
    }

    private static void publish(ApplicationContext context, EchoEvent event) {
        context.getBean(EventGateway.class)
               .publish(null, event)
               .orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS)
               .join();
    }

    private static Configuration moduleConfiguration(ApplicationContext context, String moduleName) {
        return context.getBean(AxonConfiguration.class).getModuleConfiguration(moduleName).orElseThrow();
    }

    /**
     * The names the given {@code configuration} registered event handling components under for the given
     * {@code sagaName}, across all modules.
     *
     * @param configuration the configuration of the application under test
     * @param sagaName      the simple name of the Saga type to look for
     * @return the names the given {@code configuration} registered event handling components under for the given
     * {@code sagaName}
     */
    private static List<String> sagaComponentNames(AxonConfiguration configuration, String sagaName) {
        return configuration.getModuleConfigurations()
                            .stream()
                            .flatMap(module -> module.getComponents(EventHandlingComponent.class).keySet().stream())
                            .filter(componentName -> componentName.contains(sagaName))
                            .toList();
    }

    @ContextConfiguration
    @EnableAutoConfiguration(exclude = {HibernateJpaAutoConfiguration.class, DataSourceAutoConfiguration.class})
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    static class TestContext {

        @Bean
        public TokenStore tokenStore() {
            return new InMemoryTokenStore();
        }

        @Bean
        public EventRecorder eventRecorder() {
            return new EventRecorder();
        }
    }

    @org.springframework.context.annotation.Configuration
    static class TwoStoresContext {

        @Bean
        @Primary
        public InMemorySagaStore sagaStore() {
            return new InMemorySagaStore();
        }

        @Bean
        public InMemorySagaStore secondaryStore() {
            return new InMemorySagaStore();
        }
    }

    @org.springframework.context.annotation.Configuration
    static class HistorySeedingContext {

        @Bean
        public HistoryPublisher historyPublisher(ApplicationContext applicationContext) {
            return new HistoryPublisher(applicationContext);
        }
    }

    /**
     * Publishes one event while the application context starts, in a phase before the event processors start.
     * <p>
     * Spring starts {@link SmartLifecycle} beans in ascending phase order and Axon binds an event processor's start to
     * {@link Phase#INBOUND_EVENT_CONNECTORS}, so this event is guaranteed to be in the event store before any Saga
     * processor claims a token.
     */
    static class HistoryPublisher implements SmartLifecycle {

        private final ApplicationContext applicationContext;
        private final AtomicBoolean running = new AtomicBoolean();

        HistoryPublisher(ApplicationContext applicationContext) {
            this.applicationContext = applicationContext;
        }

        @Override
        public void start() {
            publish(applicationContext, new EchoEvent(HISTORIC_EVENT_ID));
            running.set(true);
        }

        @Override
        public void stop() {
            running.set(false);
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }

        @Override
        public int getPhase() {
            return Phase.INBOUND_EVENT_CONNECTORS - 1;
        }
    }

    /**
     * Records which Saga handled which event, so a test can assert on handling without reaching into a Saga instance.
     */
    static class EventRecorder {

        private final List<String> handled = new CopyOnWriteArrayList<>();

        void record(String sagaName, String eventId) {
            handled.add(sagaName + ":" + eventId);
        }

        boolean handled(String sagaName, String eventId) {
            return handled.contains(sagaName + ":" + eventId);
        }

        List<String> handled() {
            return List.copyOf(handled);
        }
    }

    @Saga
    public static class SimpleSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "id")
        void on(EchoEvent event, EventRecorder recorder) {
            recorder.record("SimpleSaga", event.id());
        }
    }

    @Saga(sagaStore = "secondaryStore")
    public static class StoredSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "id")
        void on(EchoEvent event, EventRecorder recorder) {
            recorder.record("StoredSaga", event.id());
        }
    }

    @Saga
    @Namespace("shared-saga-processor")
    public static class FirstSharedSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "id")
        void on(EchoEvent event, EventRecorder recorder) {
            recorder.record("FirstSharedSaga", event.id());
        }
    }

    @Saga
    @Namespace("shared-saga-processor")
    public static class SecondSharedSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "id")
        void on(EchoEvent event, EventRecorder recorder) {
            recorder.record("SecondSharedSaga", event.id());
        }
    }

    record EchoEvent(String id) {

    }
}

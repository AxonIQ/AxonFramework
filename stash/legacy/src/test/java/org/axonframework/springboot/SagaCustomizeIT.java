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

package org.axonframework.springboot;

import org.axonframework.messaging.core.unitofwork.transaction.Transaction;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.messaging.unitofwork.LegacyDefaultUnitOfWork;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.spring.stereotype.Saga;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;

/**
 * Tests customization of the event handlers on a Saga, updating the configuration through an autowired method. Ensures
 * that if there are multiple threads and a logging interceptor events are still received once.
 *
 * @author Marc Gathier
 */
@SpringBootTest(properties = {
        "spring.main.banner-mode=off"
})
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
        JmxAutoConfiguration.class,
        WebClientAutoConfiguration.class
})
@EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
@Disabled("TODO #3097")
class SagaCustomizeIT {

    @Autowired
    private EventBus eventBus;
    @Autowired
    private TransactionManager transactionManager;
    @Autowired
    private AtomicInteger eventsReceived;
//    @Autowired
//    private EventProcessingModule eventProcessingModule;

    @Disabled("TODO #3443 - Adjust SagaRepository API to be async-native")
    @Test
    void publishSomeEvents() {
        publishEvent(new EchoEvent(UUID.randomUUID().toString()));
//        eventProcessingModule.eventProcessors()
//                             .forEach((name, ep) -> Assertions.assertTrue(ep.isRunning()));

//        eventProcessingModule.eventProcessors()
//                             .forEach((name, ep) -> Assertions.assertFalse(ep.isError(), "Processor ended with error"));

        await().atMost(Duration.ofSeconds(1)).until(() -> eventsReceived.get() == 1);
        publishEvent(new EchoEvent(UUID.randomUUID().toString()));
        await().atMost(Duration.ofSeconds(1)).until(() -> eventsReceived.get() == 2);
    }

    private void publishEvent(EchoEvent... events) {
        LegacyDefaultUnitOfWork.startAndGet(null).execute(
                (ctx) -> {
                    Transaction tx = transactionManager.startTransaction();
                    CurrentUnitOfWork.get().onRollback(u -> tx.rollback());
                    CurrentUnitOfWork.get().onCommit(u -> tx.commit());
                    for (EchoEvent event : events) {
                        eventBus.publish(null, asEventMessage(event));
                    }
                });
    }

    private static EventMessage asEventMessage(Object payload) {
        return new GenericEventMessage(new MessageType("event"), payload);
    }

    //    @AutoConfigureBefore(LegacyAxonAutoConfiguration.class)
//    @Configuration
    public static class Context {

//        @Bean
//        public AtomicInteger eventsReceived() {
//            return new AtomicInteger();
//        }
//
//        @Bean
//        @Primary
//        public Serializer serializer() {
//            return JacksonSerializer.defaultSerializer();
//        }
//
//        @Autowired
//        private void registerEventHandlers(EventProcessingModule eventProcessingConfiguration) throws ClassNotFoundException {
//            Set<String> registeredProcessingGroups = new HashSet<>();
//            ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
//            scanner.addIncludeFilter(new AnnotationTypeFilter(Saga.class));
//            for (BeanDefinition bd : scanner.findCandidateComponents("org.axonframework.springboot")) {
//                Class<?> aClass = Class.forName(bd.getBeanClassName());
//                String processorGroupName = eventProcessingConfiguration.sagaProcessingGroup(aClass);
//                if (!registeredProcessingGroups.contains(processorGroupName)) {
//                    eventProcessingConfiguration.registerPooledStreamingEventProcessor(
//                            processorGroupName,
//                            LegacyConfiguration::eventStore,
//                            (config, builder) -> builder
//                                    .workerExecutor(Executors.newScheduledThreadPool(
//                                            2,
//                                            new AxonThreadFactory("Worker - " + processorGroupName))
//                                    ).initialSegmentCount(2)
//                    );
//
//                    registeredProcessingGroups.add(processorGroupName);
//                }
//            }
//        }
    }

    @Saga
    @SuppressWarnings("unused")
    public static class SimpleSaga {

        @SagaEventHandler(associationProperty = "id")
        @StartSaga
        public void on(EchoEvent echoEvent, AtomicInteger eventsReceived) {
            eventsReceived.getAndIncrement();
        }
    }

    @SuppressWarnings("unused")
    public static class EchoEvent {

        private String id;

        public EchoEvent() {
        }

        public EchoEvent(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }
}

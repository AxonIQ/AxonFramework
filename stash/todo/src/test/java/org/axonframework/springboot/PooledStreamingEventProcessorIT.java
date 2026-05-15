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

import com.fasterxml.jackson.databind.JsonNode;
import org.axonframework.config.ProcessingGroup;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ListenerInvocationErrorHandler;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.axonframework.messaging.core.annotation.MessageIdentifier;
import org.axonframework.conversion.Serializer;
import org.axonframework.conversion.SimpleSerializedType;
import org.axonframework.conversion.json.JacksonSerializer;
import org.axonframework.conversion.upcasting.event.ContextAwareEventMultiUpcaster;
import org.axonframework.conversion.upcasting.event.IntermediateEventRepresentation;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.test.context.ContextConfiguration;

import java.beans.ConstructorProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Integration test for the {@link PooledStreamingEventProcessor}. Tests, for example, that all events from an
 * {@link org.axonframework.conversion.upcasting.event.EventMultiUpcaster} are read.
 *
 * @author Steven van Beelen
 */
@Disabled("TODO #3495")
class PooledStreamingEventProcessorIT {

    private ApplicationContextRunner testApplicationContext;

    @BeforeEach
    void setUp() {
        testApplicationContext = new ApplicationContextRunner().withUserConfiguration(Context.class);
    }

    @Test
    void allEventsFromMultiUpcasterAreHandled() {
        testApplicationContext.withPropertyValues("upcaster-test=true").run(context -> {
//            EventProcessingConfiguration processingConfig = context.getBean(EventProcessingConfiguration.class);
//            Optional<PooledStreamingEventProcessor> optionalProcessor =
//                    processingConfig.eventProcessor("upcaster-test", PooledStreamingEventProcessor.class);
//
//            assertTrue(optionalProcessor.isPresent());
//            PooledStreamingEventProcessor processor = optionalProcessor.get();
//            FutureUtils.joinAndUnwrap(processor.shutdown());
//
//            EventGateway eventGateway = context.getBean(EventGateway.class);
//            eventGateway.publish(null, new OriginalEvent("my-text"));
//            FutureUtils.joinAndUnwrap(processor.start());
//            await().atMost(Duration.ofMillis(500))
//                   .until(() -> processor.processingStatus().size() == 1);
//
//            UpcasterTestEventHandlingComponent eventHandlingComponent =
//                    context.getBean(UpcasterTestEventHandlingComponent.class);
//
//            assertNotNull(eventHandlingComponent);
//            await().atMost(Duration.ofMillis(500))
//                   .until(() -> eventHandlingComponent.getOriginalEventCounter().get() == 0);
//            await().atMost(Duration.ofMillis(500))
//                   .until(() -> eventHandlingComponent.getUpcastedEventCounter().get() == 2);
        });
    }

    @Test
    void lastEventIsNotHandledTwice() {
        int numberOfEvents = 100;

        testApplicationContext.withPropertyValues("once-test=true", "errorCount=2").run(context -> {
//            EventProcessingConfiguration processingConfig = context.getBean(EventProcessingConfiguration.class);
//            Optional<PooledStreamingEventProcessor> optionalProcessor =
//                    processingConfig.eventProcessor("once-test", PooledStreamingEventProcessor.class);
//
//            assertTrue(optionalProcessor.isPresent());
//            PooledStreamingEventProcessor processor = optionalProcessor.get();
//            FutureUtils.joinAndUnwrap(processor.shutdown());
//
//            EventGateway eventGateway = context.getBean(EventGateway.class);
//
//            for (int i = 0; i < numberOfEvents; i++) {
//                eventGateway.publish(null, new OriginalEvent("Event[" + i + "]"));
//            }
//            FutureUtils.joinAndUnwrap(processor.start());
//
//            // Validating for 15 or more status', as the failing segment might already have failed at this point,
//            //  resulting in 15 instead of 16 entries.
//            await().atMost(Duration.ofMillis(500))
//                   .until(() -> processor.processingStatus().size() >= 15);
//            await().atMost(Duration.ofMillis(500))
//                   .until(() -> processor.processingStatus()
//                                         .values()
//                                         .stream()
//                                         .map(EventTrackerStatus::getTrackingToken)
//                                         .filter(Objects::nonNull)
//                                         .map(TrackingToken::position)
//                                         .filter(OptionalLong::isPresent)
//                                         .map(OptionalLong::getAsLong)
//                                         .anyMatch(position -> position >= numberOfEvents));
//
//            HandlingOnceEventHandlingComponent eventHandlingComponent =
//                    context.getBean(HandlingOnceEventHandlingComponent.class);
//            assertNotNull(eventHandlingComponent);
//
//            CountDownLatch errorLatch = context.getBean(CountDownLatch.class);
//            assertNotNull(errorLatch);
//
//            assertTrue(errorLatch.await(10, TimeUnit.SECONDS));
//            FutureUtils.joinAndUnwrap(processor.shutdown());
//
//            assertFalse(eventHandlingComponent.hasHandledEventsMoreThanOnce());
        });
    }

    @ContextConfiguration
    @EnableAutoConfiguration
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    private static class Context {

        @Bean
        @Qualifier("eventSerializer")
        public Serializer eventSerializer() {
            return JacksonSerializer.defaultSerializer();
        }

//        @Bean
//        public ConfigurerModule configureEventProcessors(ListenerInvocationErrorHandler latchedErrorHandler) {
//            return configurer -> configurer.eventProcessing()
//                                           .usingPooledStreamingEventProcessors()
//                                           .registerPooledStreamingEventProcessorConfiguration(
//                                                   "upcaster-test", (config, builder) -> builder.initialSegmentCount(1)
//                                           )
//                                           .registerListenerInvocationErrorHandler(
//                                                   "once-test", c -> latchedErrorHandler
//                                           )
//                                           .registerPooledStreamingEventProcessorConfiguration(
//                                                   "once-test", (config, builder) -> builder.initialSegmentCount(16)
//                                           );
//        }

        @Bean
        @ConditionalOnProperty("upcaster-test")
        public MultiUpcaster multiUpcaster() {
            return new MultiUpcaster();
        }

        @Bean
        @ConditionalOnProperty("upcaster-test")
        public UpcasterTestEventHandlingComponent upcasterTestEventHandlingComponent() {
            return new UpcasterTestEventHandlingComponent();
        }

        @Bean
        @ConditionalOnProperty("once-test")
        public HandlingOnceEventHandlingComponent onceTestEventHandlingComponent() {
            return new HandlingOnceEventHandlingComponent();
        }

        @Bean
        public CountDownLatch errorCountLatch(@Value("${errorCount:2}") int errorCount) {
            return new CountDownLatch(errorCount);
        }

        @Bean
        public ListenerInvocationErrorHandler latchedErrorHandler(CountDownLatch errorCountLatch) {
            return (exception, event, eventHandler) -> {
                errorCountLatch.countDown();
                throw exception;
            };
        }
    }

    private static class MultiUpcaster extends ContextAwareEventMultiUpcaster<Map<Object, Object>> {

        @Override
        protected boolean canUpcast(IntermediateEventRepresentation intermediateRepresentation,
                                    Map<Object, Object> context) {
            return intermediateRepresentation.getType().getName().equals(OriginalEvent.class.getName());
        }

        @Override
        protected @NonNull Stream<IntermediateEventRepresentation> doUpcast(
                @NonNull IntermediateEventRepresentation intermediateRepresentation,
                @NonNull Map<Object, Object> context
        ) {
            return Stream.of(
                    intermediateRepresentation.upcastPayload(
                            new SimpleSerializedType(UpcastedEvent.class.getName(), null),
                            JsonNode.class,
                            // We keep the event the same, only change the type
                            node -> node
                    ),
                    intermediateRepresentation.upcastPayload(
                            new SimpleSerializedType(UpcastedEvent.class.getName(), null),
                            JsonNode.class,
                            // We keep the event the same, only change the type
                            node -> node
                    )
            );
        }

        @Override
        protected Map<Object, Object> buildContext() {
            return new HashMap<>();
        }
    }

    @ProcessingGroup("upcaster-test")
    private static class UpcasterTestEventHandlingComponent {

        private final AtomicInteger originalEventCounter = new AtomicInteger(0);
        private final AtomicInteger upcastedEventCounter = new AtomicInteger(0);

        @EventHandler
        public void on(@SuppressWarnings("unused") OriginalEvent event) {
            originalEventCounter.incrementAndGet();
        }

        @EventHandler
        public void on(@SuppressWarnings("unused") UpcastedEvent event) {
            upcastedEventCounter.incrementAndGet();
        }

        public AtomicInteger getOriginalEventCounter() {
            return originalEventCounter;
        }

        public AtomicInteger getUpcastedEventCounter() {
            return upcastedEventCounter;
        }
    }

    @ProcessingGroup("once-test")
    private static class HandlingOnceEventHandlingComponent {

        private final CopyOnWriteArraySet<String> eventIdentifiers = new CopyOnWriteArraySet<>();
        private final List<String> duplicateHandles = new CopyOnWriteArrayList<>();

        @EventHandler
        public void on(OriginalEvent event, @MessageIdentifier String eventId) {
            if (event.getTextField().equals("Event[13]")) {
                throw new RuntimeException();
            }

            boolean didNotExistYet = eventIdentifiers.add(eventId);
            if (!didNotExistYet) {
                duplicateHandles.add(eventId);
            }
        }

        public boolean hasHandledEventsMoreThanOnce() {
            return !duplicateHandles.isEmpty();
        }
    }

    @SuppressWarnings("unused")
    private static class OriginalEvent {

        private final String textField;

        @ConstructorProperties({"textField"})
        private OriginalEvent(String textField) {
            this.textField = textField;
        }

        public String getTextField() {
            return textField;
        }
    }

    @SuppressWarnings("unused")
    private static class UpcastedEvent {

        private final String textField;

        @ConstructorProperties({"textField"})
        private UpcastedEvent(String textField) {
            this.textField = textField;
        }

        public String getTextField() {
            return textField;
        }
    }
}

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

package org.axonframework.test.saga;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

import java.util.Objects;

/**
 * Test class validating the registration of {@link MessageHandlerInterceptor} instances through the
 * {@link FixtureConfiguration}.
 *
 * @author Steven van Beelen
 */
class FixtureMessageHandlerInterceptorTest {

    private static final String METADATA_KEY = "key";

    private FixtureConfiguration fixture;

    @BeforeEach
    void setUp() {
        fixture = new SagaTestFixture<>(TestSaga.class);
    }

    @Test
    @Disabled("TODO revise after Saga support is enabled")
    void registeredEventHandlerInterceptorIsInvokedBeforeHandlingEvents() {
        String testId = "some-identifier";
        String testValue = "some-value";

        fixture.registerEventHandlerInterceptor(new CustomEventHandlerInterceptor(testValue))
               .givenNoPriorActivity()
               .whenPublishingA(new SagaStartEvent(testId))
               .expectDispatchedCommands(new StartProcessCommand(testId, testValue));
    }

    private static class CustomEventHandlerInterceptor implements MessageHandlerInterceptor<EventMessage> {

        private final String value;

        private CustomEventHandlerInterceptor(String value) {
            this.value = value;
        }

        @Override
        public @NonNull MessageStream<?> interceptOnHandle(@NonNull EventMessage message,
                                                           @NonNull ProcessingContext context,
                                                           @NonNull MessageHandlerInterceptorChain<EventMessage> interceptorChain) {
            return interceptorChain.proceed(message.withMetadata(Metadata.with(METADATA_KEY, value)), context);
        }
    }

    private static class SagaStartEvent {

        @SuppressWarnings({"FieldCanBeLocal", "unused"})
        private final String identifier;

        private SagaStartEvent(String identifier) {
            this.identifier = identifier;
        }

        public String getIdentifier() {
            return identifier;
        }
    }

    private static class StartProcessCommand {

        private final String identifier;
        private final String metadataValue;

        private StartProcessCommand(String identifier, String metadataValue) {
            this.identifier = identifier;
            this.metadataValue = metadataValue;
        }

        public String getIdentifier() {
            return identifier;
        }

        public String getMetadataValue() {
            return metadataValue;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            StartProcessCommand that = (StartProcessCommand) o;
            return Objects.equals(identifier, that.identifier)
                    && Objects.equals(metadataValue, that.metadataValue);
        }

        @Override
        public int hashCode() {
            return Objects.hash(identifier, metadataValue);
        }

        @Override
        public String toString() {
            return "StartProcessCommand{" +
                    "identifier='" + identifier + '\'' +
                    ", metadataValue='" + metadataValue + '\'' +
                    '}';
        }
    }

    @SuppressWarnings("unused")
    public static class TestSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "identifier")
        public void on(SagaStartEvent event,
                       @MetadataValue(METADATA_KEY) String value,
                       CommandGateway commandGateway,
                       ProcessingContext context) {
            commandGateway.send(new StartProcessCommand(event.getIdentifier(), value), context);
        }
    }
}

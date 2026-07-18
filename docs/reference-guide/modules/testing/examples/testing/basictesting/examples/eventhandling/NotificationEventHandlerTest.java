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
package testing.basictesting.examples.eventhandling;

import testing.basictesting.fixtures.AccountCreatedEvent;
import testing.basictesting.fixtures.MoneyWithdrawnEvent;
import testing.basictesting.fixtures.SendEmailCommand;

// tag::testing-event-handling-component[]
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorModule;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class NotificationEventHandlerTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        MessagingConfigurer configurer = MessagingConfigurer.create().eventProcessing(
                processing -> processing.pooledStreaming(
                        pooledStreaming -> pooledStreaming.processor(
                                "notifications",
                                NotificationEventHandlerTest::configurePooledProcessor))
        );

        fixture = AxonTestFixture.with(configurer);
    }

    private static PooledStreamingEventProcessorModule configurePooledProcessor(
            EventProcessorModule.EventHandlingPhase<PooledStreamingEventProcessorModule, PooledStreamingEventProcessorConfiguration> processor
    ) {
        return processor.eventHandlingComponents(components -> components.autodetected(
                                "account-notifications", c -> new NotificationEventHandler())
                        )
                        .notCustomized();
    }

    @Test
    void sendNotificationOnAccountCreated() {
        fixture.given()
               .noPriorActivity()
               .when()
               .event(new AccountCreatedEvent("account-1", 500.00))
               .then()
               .await(
                       t -> t.success()
                             .commands(new SendEmailCommand(
                                     "user@example.com", "Your account has been created"
                             ))
               );
    }

    @Test
    void sendNotificationOnLowBalance() {
        fixture.given()
               .event(new AccountCreatedEvent("account-1", 500.00))
               .when()
               .event(new MoneyWithdrawnEvent("account-1", 480.00))
               .then()
               .await(
                       t -> t.success()
                             .commands(new SendEmailCommand(
                                     "user@example.com", "Low balance alert"
                             ))
               );
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }
}
// end::testing-event-handling-component[]

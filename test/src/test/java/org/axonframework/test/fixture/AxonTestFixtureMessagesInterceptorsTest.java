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

package org.axonframework.test.fixture;

import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.test.fixture.sampledomain.ChangeStudentNameCommand;
import org.axonframework.test.fixture.sampledomain.SendNotificationCommand;
import org.axonframework.test.fixture.sampledomain.StudentNameChangedEvent;
import org.junit.jupiter.api.Test;

class AxonTestFixtureMessagesInterceptorsTest {

    private static final String TEST_INTERCEPTOR_METADATA_KEY = "test-interceptor-metadata-key";
    private static final String TEST_INTERCEPTOR_METADATA_VALUE = "test-interceptor-metadata-value";

    record NotificationSentEvent(String recipientId) {}

    @Test
    void givenCommandHandler_whenCommandDispatched_thenRecordedEventContainsInterceptorMetadata() {
        var configurer = MessagingConfigurer.create(); // no disableEnhancerScanning!

        // Command handler: ChangeStudentNameCommand -> publishes StudentNameChangedEvent
        configurer.registerCommandHandlingModule(
                CommandHandlingModule.named("test-command-handler")
                                     .commandHandlers()
                                     .commandHandler(
                                             new QualifiedName(ChangeStudentNameCommand.class),
                                             (command, context) -> {
                                                 ChangeStudentNameCommand payload = (ChangeStudentNameCommand) command.payload();
                                                 EventAppender.forContext(context).append(
                                                         new StudentNameChangedEvent(payload.id(), payload.name(), 1)
                                                 );
                                                 return MessageStream.empty().cast();
                                             }
                                     )
        );

        // Event dispatch interceptor: adds TEST_INTERCEPTOR_METADATA_KEY to every published event
        // (simulates CorrelationDataInterceptor populated by CorrelationDataProvider beans)
        configurer.registerEventDispatchInterceptor(
                c -> (message, context, chain) ->
                        chain.proceed((EventMessage) message.andMetadata(Metadata.with(TEST_INTERCEPTOR_METADATA_KEY, TEST_INTERCEPTOR_METADATA_VALUE)), context)
        );

        var fixture = AxonTestFixture.with(configurer);
        fixture.when()
               .command(new ChangeStudentNameCommand("my-studentId-1", "name-1"))
               .then()
               .eventsMatch(events -> events.stream().allMatch(e -> e.metadata().containsKey(TEST_INTERCEPTOR_METADATA_KEY)));
    }

    @Test
    void givenEventHandlerDispatchesCommand_whenEventPublished_thenRecordedEventContainsInterceptorMetadata() {
        var configurer = EventSourcingConfigurer.create();

        // Pooled streaming processor: StudentNameChangedEvent -> dispatches SendNotificationCommand
        configurer.messaging(mc -> mc.eventProcessing(ep -> ep.pooledStreaming(ps -> ps.processor(
                EventProcessorModule
                        .pooledStreaming("test-interceptor-metadata")
                        .eventHandlingComponents(c -> c.declarative(
                                cfg -> SimpleEventHandlingComponent.create("test-handler").subscribe(
                                        new QualifiedName(StudentNameChangedEvent.class),
                                        (event, ctx) -> {
                                            CommandDispatcher.forContext(ctx)
                                                             .send(new SendNotificationCommand(
                                                                     event.payloadAs(StudentNameChangedEvent.class).id(),
                                                                     "Name changed"
                                                             ));
                                            return MessageStream.empty();
                                        }
                                )
                        )).notCustomized()
        ))));

        // Command handler: SendNotificationCommand -> publishes NotificationSentEvent
        configurer.messaging(mc -> mc.registerCommandHandlingModule(
                CommandHandlingModule.named("test-notification-handler")
                                     .commandHandlers()
                                     .commandHandler(
                                             new QualifiedName(SendNotificationCommand.class),
                                             (command, context) -> {
                                                 SendNotificationCommand payload = (SendNotificationCommand) command.payload();
                                                 EventAppender.forContext(context).append(
                                                         new NotificationSentEvent(payload.recipientId())
                                                 );
                                                 return MessageStream.empty().cast();
                                             }
                                     )
        ));

        // Event dispatch interceptor on EventStore (EventSourcingConfigurer uses EventStore)
        configurer.messaging(mc -> mc.registerEventDispatchInterceptor(
                c -> (message, context, chain) ->
                        chain.proceed((EventMessage) message.andMetadata(Metadata.with(TEST_INTERCEPTOR_METADATA_KEY, TEST_INTERCEPTOR_METADATA_VALUE)), context)
        ));

        var fixture = AxonTestFixture.with(configurer);

        var studentNameChanged = new StudentNameChangedEvent("my-studentId-1", "name-1", 1);
        fixture.given()
               .events(studentNameChanged)
               .then()
               // First, await that NotificationSentEvent was published at all
               .await(r -> r.eventsMatch(events -> events.stream()
                                                         .anyMatch(e -> e.payloadType().equals(NotificationSentEvent.class))))
               // Then assert it carries the interceptor metadata, fails until the recording bug is fixed
               .eventsMatch(events -> events.stream()
                                            .filter(e -> e.payloadType().equals(NotificationSentEvent.class))
                                            .allMatch(e -> e.metadata().containsKey(TEST_INTERCEPTOR_METADATA_KEY)));
    }
}

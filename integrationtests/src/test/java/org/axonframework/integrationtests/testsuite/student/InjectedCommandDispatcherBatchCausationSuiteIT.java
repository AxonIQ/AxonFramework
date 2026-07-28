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

package org.axonframework.integrationtests.testsuite.student;

import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.integrationtests.testsuite.student.events.StudentEnrolledEvent;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Verifies that when a {@link PooledStreamingEventProcessor} handles multiple events within a single batch, an
 * automation reacting to each event resolves its own {@link CommandDispatcher} via
 * {@link CommandDispatcher#forContext(org.axonframework.messaging.core.unitofwork.ProcessingContext) forContext}, and
 * every command it dispatches carries the {@code causationId} and {@code correlationId} of its own triggering event.
 *
 * @author Mateusz Nowak
 */
class InjectedCommandDispatcherBatchCausationSuiteIT extends AbstractStudentIT {

    private static final String PROCESSOR_NAME = "when-student-enrolled-then-notify";

    private final List<DispatchedCommand> dispatchedCommands = new CopyOnWriteArrayList<>();


    @Test
    void bothEventsInOneBatchDispatchCommandsCarryingTheirOwnCausationId() {
        // given
        startApp();
        var studentId = UUID.randomUUID().toString();

        // seed two events and let the initial (live) delivery settle — this advances the token past both events
        var eventIdByCourse = storeTwoEnrollmentsInOneBatch(studentId, "course-1", "course-2");
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> assertEquals(2, dispatchedCommands.size(),
                                                 "both enrollment events should have dispatched a command"));

        // when — replay the (now already-processed) events. A replay reads them as a backlog, which the pooled
        // processor groups into a SINGLE batch (batchSize 2, one segment) sharing one root ProcessingContext, so
        // both events are handled within it. (Live tailing delivers one event per batch; resetTokens only replays
        // up to the current token, hence the initial live pass above.)
        replayAlreadyProcessedEvents();

        // then
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> assertEquals(2, dispatchedCommands.size(),
                                                 "the replayed batch should have dispatched a command per event"));

        var byCourse = dispatchedCommands.stream()
                                         .collect(Collectors.toMap(DispatchedCommand::courseId, Function.identity()));
        var command1 = byCourse.get("course-1");
        var command2 = byCourse.get("course-2");

        // each command must be caused by its own triggering event
        assertEquals(eventIdByCourse.get("course-1"), command1.causationId(),
                     "command for event #1 must carry event #1's causationId");
        assertEquals(eventIdByCourse.get("course-2"), command2.causationId(),
                     "command for event #2 must carry event #2's causationId, not event #1's");

        // ... and therefore the causation/correlation of the two commands differ
        assertNotEquals(command1.causationId(), command2.causationId(),
                        "the two commands must not share a causationId");
        assertNotEquals(command1.correlationId(), command2.correlationId(),
                        "the two commands must not share a correlationId");
    }

    private Map<String, String> storeTwoEnrollmentsInOneBatch(String studentId, String course1, String course2) {
        var message1 = new GenericEventMessage(new MessageType(StudentEnrolledEvent.class),
                                               new StudentEnrolledEvent(studentId, course1),
                                               Metadata.emptyInstance());
        var message2 = new GenericEventMessage(new MessageType(StudentEnrolledEvent.class),
                                               new StudentEnrolledEvent(studentId, course2),
                                               Metadata.emptyInstance());
        UnitOfWork uow = unitOfWorkFactory.create();
        // Publish BOTH events in a single call => one atomic append => the pooled processor reads them
        // in ONE batch (sharing a single root ProcessingContext).
        uow.runOnInvocation(context -> context.component(EventSink.class).publish(context, message1, message2));
        uow.execute().join();
        return Map.of(course1, message1.identifier(), course2, message2.identifier());
    }

    private void replayAlreadyProcessedEvents() {
        PooledStreamingEventProcessor processor =
                (PooledStreamingEventProcessor) startedConfiguration.getComponents(EventProcessor.class)
                                                                    .get(PROCESSOR_NAME);
        processor.shutdown()
                 .thenCompose(ignored -> processor.resetTokens())
                 .join();
        dispatchedCommands.clear();
        processor.start().join();
    }

    @Override
    protected EventSourcingConfigurer testSuiteConfigurer(EventSourcingConfigurer configurer) {
        CommandHandlingModule commandHandler = CommandHandlingModule
                .named("notify-student-command-handler")
                .commandHandlers()
                .autodetectedCommandHandlingComponent(cfg -> new NotifyStudentCommandHandler(dispatchedCommands))
                .build();
        configurer.registerCommandHandlingModule(commandHandler);

        var automationProcessor = EventProcessorModule
                .pooledStreaming(PROCESSOR_NAME)
                .eventHandlingComponents(components -> components.autodetected(
                        "notifyAutomation",
                        cfg -> new NotifyOnEnrollmentAutomation()
                ))
                // one segment + batch of two => both events are consumed in a single batch/UnitOfWork
                .customized((cfg, c) -> c.initialSegmentCount(1).batchSize(2));

        return configurer.messaging(
                messaging -> messaging.eventProcessing(
                        ep -> ep.pooledStreaming(ps -> ps.processor(automationProcessor))
                )
        );
    }

    record NotifyStudentCommand(String studentId, String courseId) {

    }

    record DispatchedCommand(String courseId, String causationId, String correlationId) {

    }

    /**
     * Reacts to every {@link StudentEnrolledEvent} by dispatching a command through the <em>injected</em>
     * {@link CommandDispatcher} - i.e. resolved via {@code forContext(context)} on each invocation.
     */
    static class NotifyOnEnrollmentAutomation {

        @EventHandler
        MessageStream.Empty<?> react(StudentEnrolledEvent event, CommandDispatcher commandDispatcher) {
            return MessageStream.fromFuture(
                    commandDispatcher.send(new NotifyStudentCommand(event.studentId(), event.courseId())).getResultMessage()
            ).ignoreEntries();
        }
    }

    /**
     * Captures the correlation/causation metadata each dispatched command actually arrived with.
     */
    static class NotifyStudentCommandHandler {

        private final List<DispatchedCommand> captured;

        NotifyStudentCommandHandler(List<DispatchedCommand> captured) {
            this.captured = captured;
        }

        @CommandHandler
        MessageStream.Single<?> handle(NotifyStudentCommand command,
                                       @MetadataValue("causationId") String causationId,
                                       @MetadataValue("correlationId") String correlationId) {
            captured.add(new DispatchedCommand(command.courseId(), causationId, correlationId));
            return MessageStream.just(SUCCESSFUL_COMMAND_RESULT);
        }
    }
}

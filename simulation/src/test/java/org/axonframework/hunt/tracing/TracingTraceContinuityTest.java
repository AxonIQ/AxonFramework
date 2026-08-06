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

package org.axonframework.hunt.tracing;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.tracing.TracingEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.tracing.TracingEventStore;
import org.axonframework.hunt.tracing.RecordingSpanFactory.Kind;
import org.axonframework.hunt.tracing.RecordingSpanFactory.RecordedSpan;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.tracing.TracingCommandBus;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.tracing.SpanFactory;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hunt probe for the tracing rework: end-to-end trace continuity from command dispatch through command handling,
 * event publication, storage-engine append, and pooled-streaming event processing; span lifecycle on the command
 * failure path; and single-decoration of the event store stack by the tracing configuration enhancers.
 */
class TracingTraceContinuityTest {

    private static final String DISPATCH_PREFIX = "CommandBus.dispatch";
    private static final String HANDLE_PREFIX = "CommandBus.handle";
    private static final String PUBLISH_PREFIX = "EventSink.publish";
    private static final String APPEND_SPAN = "EventStorageEngine.appendTransaction";
    private static final String BATCH_SPAN = "StreamingEventProcessor.batch";
    private static final String PROCESS_PREFIX = "EventProcessor.process";

    private final RecordingSpanFactory spanFactory = new RecordingSpanFactory();
    private final AtomicInteger handledEvents = new AtomicInteger();
    private AxonConfiguration configuration;

    record Ping(String id) {

    }

    record Boom(String id) {

    }

    record Pong(String id) {

    }

    @AfterEach
    void tearDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    private void startApp() {
        var commandModule = CommandHandlingModule
                .named("hunt-commands")
                .commandHandlers()
                .commandHandler(new QualifiedName(Ping.class), cfg -> {
                    EventSink sink = cfg.getComponent(EventStore.class);
                    return (command, context) -> {
                        Ping ping = (Ping) command.payload();
                        EventMessage event =
                                new GenericEventMessage(new MessageType(Pong.class), new Pong(ping.id()));
                        return MessageStream.fromFuture(
                                sink.publish(context, List.of(event))
                                    .thenApply(v -> new GenericCommandResultMessage(new MessageType("pong-ack"),
                                                                                    "ok")));
                    };
                })
                .commandHandler(new QualifiedName(Boom.class), cfg -> (command, context) -> {
                    throw new IllegalStateException("boom");
                });

        var processorModule = EventProcessorModule
                .pooledStreaming("hunt-processor")
                .eventHandlingComponents(c -> c.declarative(
                        "hunt-projection",
                        cfg -> SimpleEventHandlingComponent
                                .create("hunt-projection")
                                .subscribe(new QualifiedName(Pong.class), (event, ctx) -> {
                                    handledEvents.incrementAndGet();
                                    return MessageStream.empty();
                                })))
                .notCustomized();

        configuration = EventSourcingConfigurer
                .create()
                .componentRegistry(r -> {
                    // The Axon Server connector sits on this module's test classpath for other arms; keep this app
                    // self-contained on the in-memory engine instead of dialing a server that is not there.
                    r.disableEnhancer(io.axoniq.framework.axonserver.connector.configuration
                                              .AxonServerConfigurationEnhancer.class);
                    r.registerComponent(EventStorageEngine.class,
                                        c -> new org.axonframework.eventsourcing.eventstore.inmemory
                                                .InMemoryEventStorageEngine());
                    r.registerComponent(SpanFactory.class, c -> spanFactory);
                })
                .registerCommandHandlingModule(commandModule)
                .modelling(m -> m.messaging(ms -> ms.eventProcessing(
                        ep -> ep.pooledStreaming(ps -> ps.processor(processorModule)))))
                .start();
    }

    @Test
    void spanTreeIsConnectedFromDispatchToPooledProcessorHandling() {
        // given
        startApp();
        CommandBus commandBus = configuration.getComponent(CommandBus.class);

        // when
        commandBus.dispatch(new GenericCommandMessage(new MessageType(Ping.class), new Ping("p1")), null)
                  .orTimeout(30, TimeUnit.SECONDS)
                  .join();
        Awaitility.await().atMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(spanFactory.byPrefix(PROCESS_PREFIX))
                          .as("the pooled processor's handler span must appear and end; spans so far:\n"
                                      + spanFactory.render())
                          .anyMatch(RecordedSpan::ended));

        // then
        RecordedSpan dispatch = spanFactory.onlyByPrefix(DISPATCH_PREFIX);
        RecordedSpan handle = spanFactory.onlyByPrefix(HANDLE_PREFIX);
        RecordedSpan publish = spanFactory.onlyByPrefix(PUBLISH_PREFIX);
        RecordedSpan append = spanFactory.onlyByPrefix(APPEND_SPAN);
        RecordedSpan process = spanFactory.onlyByPrefix(PROCESS_PREFIX);
        List<RecordedSpan> batches = spanFactory.byPrefix(BATCH_SPAN);

        // the command handler span continues the dispatch span's trace via propagated metadata
        assertThat(handle.kind()).isEqualTo(Kind.HANDLER);
        assertThat(handle.parentId()).as("handler span must be the dispatch span's child").isEqualTo(dispatch.id());

        // the publish span nests under the command handler span via the context's active scope
        assertThat(publish.parentId()).as("publish span must be the command-handle span's child\n"
                                                  + spanFactory.render()).isEqualTo(handle.id());

        // the append span nests under the command handler span too (commit runs on the same context)
        assertThat(append.parentId()).as("append span must be the command-handle span's child\n"
                                                 + spanFactory.render()).isEqualTo(handle.id());

        // the streaming handler span parents under the batch span and links back to the publisher
        assertThat(batches).as("a streaming batch span must exist\n" + spanFactory.render()).isNotEmpty();
        assertThat(process.kind()).isEqualTo(Kind.CONTEXT_PARENT_HANDLER);
        assertThat(batches).extracting(RecordedSpan::id)
                           .as("process span must parent under the batch span\n" + spanFactory.render())
                           .contains(process.parentId());
        assertThat(process.linkId())
                .as("process span must link back to the publish span through metadata that survived the store\n"
                            + spanFactory.render())
                .isEqualTo(publish.id());
        assertThat(process.attributes()).containsEntry("axoniq.event_processor.name", "hunt-processor");

        // every opened span must eventually end
        Awaitility.await().atMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(spanFactory.openSpans())
                          .as("no span may be left open\n" + spanFactory.render())
                          .isEmpty());
        assertThat(handledEvents.get()).isPositive();
    }

    @Test
    void failingCommandHandlerEndsAndErrorMarksDispatchAndHandleSpans() {
        // given
        startApp();
        CommandBus commandBus = configuration.getComponent(CommandBus.class);

        // when
        assertThatThrownBy(() -> commandBus
                .dispatch(new GenericCommandMessage(new MessageType(Boom.class), new Boom("b1")), null)
                .orTimeout(30, TimeUnit.SECONDS)
                .join())
                .hasRootCauseInstanceOf(IllegalStateException.class);

        // then
        Awaitility.await().atMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(spanFactory.openSpans())
                          .as("every span opened for the failed command must end\n" + spanFactory.render())
                          .isEmpty());
        RecordedSpan dispatch = spanFactory.onlyByPrefix(DISPATCH_PREFIX);
        RecordedSpan handle = spanFactory.onlyByPrefix(HANDLE_PREFIX);
        assertThat(dispatch.error()).as("dispatch span must record the failure").isNotNull();
        assertThat(handle.error()).as("handle span must record the failure").isNotNull();
    }

    @Test
    void tracingDecoratorsWrapEachComponentExactlyOnce() {
        // given
        startApp();

        // when / then
        EventStore eventStore = configuration.getComponent(EventStore.class);
        assertThat(countLayers(eventStore, TracingEventStore.class))
                .as("EventStore chain: " + chain(eventStore)).isEqualTo(1);

        EventStorageEngine engine = configuration.getComponent(EventStorageEngine.class);
        assertThat(countLayers(engine, TracingEventStorageEngine.class))
                .as("EventStorageEngine chain: " + chain(engine)).isEqualTo(1);

        CommandBus commandBus = configuration.getComponent(CommandBus.class);
        assertThat(countLayers(commandBus, TracingCommandBus.class))
                .as("CommandBus chain: " + chain(commandBus)).isEqualTo(1);
    }

    private static int countLayers(Object component, Class<?> layerType) {
        int count = 0;
        for (Object current = component; current != null; current = delegateOf(current)) {
            if (layerType.isInstance(current)) {
                count++;
            }
        }
        return count;
    }

    private static String chain(Object component) {
        StringBuilder sb = new StringBuilder();
        for (Object current = component; current != null; current = delegateOf(current)) {
            if (!sb.isEmpty()) {
                sb.append(" -> ");
            }
            sb.append(current.getClass().getSimpleName());
        }
        return sb.toString();
    }

    private static Object delegateOf(Object component) {
        for (Class<?> type = component.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("delegate");
                field.setAccessible(true);
                return field.get(component);
            } catch (NoSuchFieldException ignored) {
                // walk up
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return null;
    }
}

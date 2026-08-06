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

import io.micrometer.context.ContextRegistry;
import org.axonframework.hunt.tracing.RecordingSpanFactory.RecordedSpan;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.FluxUtils;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.tracing.TracingEventHandlingComponent;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hunt probes for the tracing rework's span-scope machinery at the unit level, against production
 * {@link UnitOfWork} processing contexts (never the test stub): batch abort lifecycle, concurrent branch-scoped
 * streams on a shared context, and the reactive ThreadLocal capture through {@link FluxUtils}.
 */
class TracingScopeLifecycleTest {

    private static final String PROCESS_PREFIX = "EventProcessor.process";
    private static final String BATCH_SPAN = "StreamingEventProcessor.batch";

    private final RecordingSpanFactory factory = new RecordingSpanFactory();
    private final UnitOfWorkFactory uowFactory = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE);

    record Tick(int n) {

    }

    private static EventMessage tick(int n) {
        return new GenericEventMessage(new MessageType(Tick.class), new Tick(n));
    }

    private RecordedSpan spanForMessage(String prefix, EventMessage event) {
        return factory.byPrefix(prefix).stream()
                      .filter(s -> event.identifier().equals(s.messageId()))
                      .findFirst()
                      .orElseThrow(() -> new AssertionError(
                              "no span with prefix '" + prefix + "' for message " + event.identifier()
                                      + "\n" + factory.render()));
    }

    @Nested
    class BatchFailureLifecycle {

        @Test
        void batchAbortMidwayEndsEverySpanAndErrorMarksTheFailingOne() {
            // given a streaming-topology tracing component whose second event fails the batch
            EventHandlingComponent delegate = SimpleEventHandlingComponent
                    .create("hunt-projection")
                    .subscribe(new QualifiedName(Tick.class), (event, ctx) -> {
                        Tick t = (Tick) event.payload();
                        if (t.n() == 2) {
                            return MessageStream.failed(new IllegalStateException("batch-abort"));
                        }
                        return MessageStream.empty();
                    });
            TracingEventHandlingComponent tracing = new TracingEventHandlingComponent(
                    delegate, factory, "hunt-processor", true, true, false, Duration.ofMinutes(2));
            EventMessage first = tick(1);
            EventMessage second = tick(2);
            UnitOfWork uow = uowFactory.create();

            // when the batch handles event 1, then aborts on event 2
            assertThatThrownBy(() -> uow.executeWithResult(
                    ctx -> tracing.handle(first, ctx).asCompletableFuture()
                                  .thenCompose(x -> tracing.handle(second, ctx).asCompletableFuture()))
                                        .orTimeout(30, TimeUnit.SECONDS)
                                        .join())
                    .hasRootCauseInstanceOf(IllegalStateException.class);

            // then every span opened for the batch ends, and only the failing event's span is error-marked
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(factory.openSpans())
                              .as("no span may be left open after a batch abort\n" + factory.render())
                              .isEmpty());
            RecordedSpan firstSpan = spanForMessage(PROCESS_PREFIX, first);
            RecordedSpan secondSpan = spanForMessage(PROCESS_PREFIX, second);
            RecordedSpan batch = factory.onlyByPrefix(BATCH_SPAN);
            assertThat(firstSpan.error()).as("the succeeding event's span must not be error-marked").isNull();
            assertThat(secondSpan.error()).as("the failing event's span must record the failure\n" + factory.render())
                                          .isInstanceOf(IllegalStateException.class);
            assertThat(batch.error()).as("the batch span must record the batch failure\n" + factory.render())
                                     .isNotNull();
            assertThat(firstSpan.parentId()).isEqualTo(batch.id());
            assertThat(secondSpan.parentId()).isEqualTo(batch.id());
        }
    }

    @Nested
    class ConcurrentBranchScopes {

        @Test
        void interleavedPullsOnTwoThreadsKeepEveryChildUnderItsOwnHandlerSpan() {
            // given two workers sharing one production processing context, plus a root-churn thread
            int rounds = 40;
            Queue<String> violations = new ConcurrentLinkedQueue<>();
            UnitOfWork uow = uowFactory.create();

            // when both workers run branch-scoped streams with interleaved pulls
            uow.executeWithResult(ctx -> {
                   CyclicBarrier barrier = new CyclicBarrier(2);
                   Function<Integer, Runnable> workerFactory = workerIndex -> () -> {
                       for (int k = 0; k < rounds; k++) {
                           EventMessage msg = tick(k);
                           String suffix = workerIndex + "-" + k;
                           RecordedSpan handlerSpan =
                                   (RecordedSpan) factory.createHandlerSpan("worker-" + suffix, msg, ctx);
                           // The entry hides behind a future completed only after construction, so the pull that
                           // maps it MUST go through SpanScopedMessageStream#fetchNext -- the construction-time
                           // probe pull inside Span#branchStream cannot consume it early.
                           CompletableFuture<EventMessage> gate = new CompletableFuture<>();
                           MessageStream<EventMessage> stream = handlerSpan.branchStream(ctx, branched -> {
                               RecordedSpan constructChild = (RecordedSpan) factory.createDispatchSpan(
                                       "child-construct-" + suffix, msg, branched);
                               constructChild.branch(branched, ignored -> null);
                               return MessageStream.fromFuture(gate).mapMessage(m -> {
                                   RecordedSpan current = factory.currentSpan();
                                   if (current == null || current.id() != handlerSpan.id()) {
                                       violations.add("pull of worker-" + suffix + " ran within "
                                                              + (current == null ? "no scope" : current.name()));
                                   }
                                   RecordedSpan pullChild = (RecordedSpan) factory.createDispatchSpan(
                                           "child-pull-" + suffix, msg, branched);
                                   pullChild.branch(branched, ignored -> null);
                                   return m;
                               });
                           });
                           try {
                               barrier.await(30, TimeUnit.SECONDS);
                           } catch (Exception e) {
                               violations.add("barrier failure: " + e);
                               return;
                           }
                           gate.complete(msg);
                           while (stream.next().isPresent()) {
                               // drain interleaved with the other worker
                           }
                           stream.close();
                           if (factory.currentSpan() != null) {
                               violations.add("scope leaked onto " + Thread.currentThread().getName()
                                                      + " after worker-" + suffix);
                           }
                       }
                   };
                   Thread worker1 = new Thread(workerFactory.apply(1), "hunt-worker-1");
                   Thread worker2 = new Thread(workerFactory.apply(2), "hunt-worker-2");
                   Thread noise = new Thread(() -> {
                       // root-context churn: lifecycle-covering spans rewrite the root's active scope concurrently
                       for (int k = 0; k < 100; k++) {
                           factory.createRootSpan("noise", ctx).coverLifecycle(ctx);
                       }
                   }, "hunt-noise");
                   worker1.start();
                   worker2.start();
                   noise.start();
                   try {
                       worker1.join(60_000);
                       worker2.join(60_000);
                       noise.join(60_000);
                   } catch (InterruptedException e) {
                       Thread.currentThread().interrupt();
                       throw new IllegalStateException(e);
                   }
                   return CompletableFuture.completedFuture(null);
               })
               .orTimeout(120, TimeUnit.SECONDS)
               .join();

            // then no pull observed a foreign scope and every child parents under its own worker's handler span
            assertThat(violations).isEmpty();
            Map<String, Long> handlerIdsByName = factory.byPrefix("worker-").stream()
                                                        .collect(Collectors.toMap(RecordedSpan::name,
                                                                                  RecordedSpan::id));
            List<RecordedSpan> children = factory.byPrefix("child-");
            assertThat(children).hasSize(2 * 2 * rounds);
            for (RecordedSpan child : children) {
                String suffix = child.name().substring(child.name().indexOf("-", "child-".length()) + 1);
                Long expectedParent = handlerIdsByName.get("worker-" + suffix);
                assertThat(child.parentId())
                        .as("child '%s' must parent under 'worker-%s'\n%s", child.name(), suffix, factory.render())
                        .isEqualTo(expectedParent);
            }
            // and every span opened during the run has ended with the context
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(factory.openSpans())
                              .as("no span may be left open\n" + factory.render())
                              .isEmpty());
        }
    }

    @Nested
    class ReactiveThreadLocalCapture {

        private static final String ACCESSOR_KEY = "hunt.current.span";

        private final ExecutorService reactiveExecutor =
                Executors.newSingleThreadExecutor(r -> new Thread(r, "hunt-reactive"));
        private final ThreadLocal<String> restored = new ThreadLocal<>();

        @BeforeEach
        void registerAccessor() {
            ContextRegistry.getInstance().registerThreadLocalAccessor(
                    ACCESSOR_KEY,
                    () -> {
                        RecordedSpan current = factory.currentSpan();
                        return current == null ? null : current.name();
                    },
                    restored::set,
                    restored::remove);
        }

        @AfterEach
        void tearDown() {
            ContextRegistry.getInstance().removeThreadLocalAccessor(ACCESSOR_KEY);
            reactiveExecutor.shutdownNow();
        }

        @Test
        void handlerFluxCompletingOnAnotherThreadStillSeesTheHandlerSpanAndLeavesNoResidue() throws Exception {
            // given a reactive handler whose Flux completes on a dedicated scheduler thread
            AtomicReference<ContextView> observed = new AtomicReference<>();
            EventMessage event = tick(1);
            EventHandlingComponent delegate = SimpleEventHandlingComponent
                    .create("hunt-reactive")
                    .subscribe(new QualifiedName(Tick.class), (e, ctx) -> FluxUtils.asMessageStream(
                            Flux.deferContextual(view -> {
                                observed.set(view);
                                return Flux.just((Message) tick(99))
                                           .publishOn(Schedulers.fromExecutorService(reactiveExecutor));
                            })).ignoreEntries());
            TracingEventHandlingComponent tracing = new TracingEventHandlingComponent(delegate, factory);

            // when the event is handled on a production context
            uowFactory.create()
                      .executeWithResult(ctx -> tracing.handle(event, ctx).asCompletableFuture())
                      .orTimeout(30, TimeUnit.SECONDS)
                      .join();

            // then the handler span's scope was captured into the Reactor context at subscription
            RecordedSpan process = spanForMessage(PROCESS_PREFIX, event);
            assertThat(observed.get()).as("the handler flux must have been subscribed").isNotNull();
            assertThat(observed.get().<String>getOrDefault(ACCESSOR_KEY, null))
                    .as("the handler span active at subscription must be captured into the Reactor context\n"
                                + factory.render())
                    .isEqualTo(process.name());

            // and the span ended, with nothing left behind on the scheduler thread
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(factory.openSpans()).isEmpty());
            assertThat(reactiveExecutor.submit(() -> factory.currentSpan()).get(10, TimeUnit.SECONDS))
                    .as("no span scope may remain active on the scheduler thread").isNull();
            assertThat(reactiveExecutor.submit(restored::get).get(10, TimeUnit.SECONDS))
                    .as("no restored ThreadLocal value may remain on the scheduler thread").isNull();
        }

        @Test
        void handlerFluxErroringOnAnotherThreadEndsAndErrorMarksTheHandlerSpan() {
            // given a reactive handler whose Flux errors on a dedicated scheduler thread
            EventMessage event = tick(2);
            EventHandlingComponent delegate = SimpleEventHandlingComponent
                    .create("hunt-reactive")
                    .subscribe(new QualifiedName(Tick.class), (e, ctx) -> FluxUtils.asMessageStream(
                            Flux.just((Message) tick(99))
                                .publishOn(Schedulers.fromExecutorService(reactiveExecutor))
                                .concatWith(Flux.error(new IllegalStateException("reactive-boom")))
                    ).ignoreEntries());
            TracingEventHandlingComponent tracing = new TracingEventHandlingComponent(delegate, factory);

            // when / then the failure surfaces and the handler span ends error-marked
            assertThatThrownBy(() -> uowFactory.create()
                                               .executeWithResult(
                                                       ctx -> tracing.handle(event, ctx).asCompletableFuture())
                                               .orTimeout(30, TimeUnit.SECONDS)
                                               .join())
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(factory.openSpans())
                              .as("no span may be left open after a reactive failure\n" + factory.render())
                              .isEmpty());
            RecordedSpan process = spanForMessage(PROCESS_PREFIX, event);
            assertThat(process.error()).isInstanceOf(IllegalStateException.class);
        }
    }
}

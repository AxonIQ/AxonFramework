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

package org.axonframework.hunt.probe.tracing;

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycle.Phase;
import org.axonframework.messaging.core.unitofwork.ProcessingLifecycleInterceptor;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.tracing.TracingEventHandlingComponent;
import org.axonframework.messaging.tracing.LoggingSpanFactory;
import org.axonframework.common.FutureUtils;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pass-through transparency of the event-handling tracing decorator (claim C45 in
 * {@code docs/testing-plans/axon-hunt.md}).
 * <p>
 * When tracing is disabled no decorator is installed at all, so the disabled path is the bare delegate by
 * construction. The transparency question is therefore about the installed decorator: wrapping an
 * {@link EventHandlingComponent} in {@link TracingEventHandlingComponent} (streaming topology, batch span enabled)
 * must not reorder deliveries, drop deliveries, or change the phase-action dispatch of the enclosing unit of work.
 * The probe runs the same 200-event workload through the bare delegate and through the decorated one, each inside
 * its own {@link UnitOfWork} carrying a recording {@link ProcessingLifecycleInterceptor}, and compares delivered
 * payload sequences and per-phase dispatch sequences.
 * <p>
 * The decorator does add context-completion work that is part of its design (the batch span's
 * lifecycle binding and one close-backstop per event, registered via {@code doFinally}); those are completion
 * handlers, not phase actions, and the probe asserts the phase-action sequence alone is unchanged.
 */
class TracingDecoratorTransparencyTest {

    private static final int EVENT_COUNT = 200;

    private record Dispatch(String kind, int phaseOrder) {

    }

    private static final class RecordingInterceptor implements ProcessingLifecycleInterceptor {

        private final List<Dispatch> dispatches = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<?> interceptPhase(ProcessingContext context, Phase phase,
                                                   Supplier<CompletableFuture<?>> action) {
            dispatches.add(new Dispatch("phase", phase.order()));
            return action.get();
        }

        @Override
        public void interceptCompletion(ProcessingContext context, Runnable action) {
            dispatches.add(new Dispatch("completion", Integer.MAX_VALUE));
            action.run();
        }

        @Override
        public void interceptError(ProcessingContext context, Phase failedPhase, Throwable cause, Runnable action) {
            dispatches.add(new Dispatch("error", failedPhase == null ? Integer.MIN_VALUE : failedPhase.order()));
            action.run();
        }
    }

    private static final class RecordingDelegate implements EventHandlingComponent {

        private final List<String> delivered = new ArrayList<>();

        @Override
        public MessageStream.Empty<Message> handle(EventMessage event, ProcessingContext context) {
            delivered.add((String) event.payload());
            return MessageStream.empty();
        }

        @Override
        public Set<QualifiedName> supportedEvents() {
            return Set.of();
        }

        @Override
        public Object sequenceIdentifierFor(EventMessage event, ProcessingContext context) {
            return event.identifier();
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            descriptor.describeProperty("type", "RecordingDelegate");
        }
    }

    /**
     * Runs the workload through the given component inside one unit of work (one batch context), returning the
     * delegate's delivered payloads and the interceptor's recorded dispatches.
     */
    private record RunResult(List<String> delivered, List<Dispatch> dispatches, int completedStreams) {

    }

    private RunResult run(java.util.function.Function<RecordingDelegate, EventHandlingComponent> componentFactory) {
        RecordingDelegate delegate = new RecordingDelegate();
        EventHandlingComponent component = componentFactory.apply(delegate);
        RecordingInterceptor interceptor = new RecordingInterceptor();
        UnitOfWork unitOfWork = new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE)
                .create("transparency-probe", configuration -> configuration.addLifecycleInterceptor(interceptor));

        List<MessageStream.Empty<Message>> results = new ArrayList<>();
        unitOfWork.runOnInvocation(context -> {
            for (int i = 0; i < EVENT_COUNT; i++) {
                EventMessage event = new GenericEventMessage(new MessageType("ProbeEvent"), "payload-" + i);
                results.add(component.handle(event, context));
            }
        });
        FutureUtils.joinAndUnwrap(unitOfWork.execute(), Duration.ofSeconds(30));

        int completed = (int) results.stream().filter(MessageStream::isCompleted).count();
        return new RunResult(delegate.delivered, interceptor.dispatches, completed);
    }

    @Test
    void decoratedComponentDeliversTheSameEventsInTheSameOrderWithTheSamePhaseDispatches() {
        // given / when the same workload runs bare and decorated (streaming topology, batch span enabled)
        RunResult bare = run(delegate -> delegate);
        RunResult decorated = run(delegate -> new TracingEventHandlingComponent(
                delegate, LoggingSpanFactory.INSTANCE, "probe-processor", /* streaming */ true,
                /* batchTraceEnabled */ true, /* distributedInSameTrace */ false, Duration.ofMinutes(2)));

        // then no delivery is dropped or reordered
        assertThat(decorated.delivered()).hasSize(EVENT_COUNT);
        assertThat(decorated.delivered()).containsExactlyElementsOf(bare.delivered());

        // then every per-event result stream terminated (the decorator's stream wrapper drops no completion)
        assertThat(decorated.completedStreams()).isEqualTo(bare.completedStreams()).isEqualTo(EVENT_COUNT);

        // then the phase-action dispatch sequence of the unit of work is unchanged; the decorator's additions are
        // completion handlers only (span-close backstops), measured here rather than asserted away
        List<Dispatch> barePhases = bare.dispatches().stream().filter(d -> d.kind().equals("phase")).toList();
        List<Dispatch> decoratedPhases =
                decorated.dispatches().stream().filter(d -> d.kind().equals("phase")).toList();
        assertThat(decoratedPhases).containsExactlyElementsOf(barePhases);
        assertThat(decorated.dispatches().stream().noneMatch(d -> d.kind().equals("error"))).isTrue();
    }
}

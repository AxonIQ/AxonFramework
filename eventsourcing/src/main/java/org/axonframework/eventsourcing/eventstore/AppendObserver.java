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

package org.axonframework.eventsourcing.eventstore;

/**
 * Callback receiving the terminal outcome of every append that passes through an
 * {@link AppendObservingEventStorageEngine}.
 * <p>
 * An observer is told, at most once per append, whether the append was
 * {@link AppendObservation.Outcome.Committed committed},
 * {@link AppendObservation.Outcome.Rejected rejected} because its {@link AppendCondition} was violated,
 * {@link AppendObservation.Outcome.Failed failed} for any other reason, or
 * {@link AppendObservation.Outcome.RolledBack rolled back}. This makes it a single place to log, count, or otherwise
 * react to append outcomes, including the optimistic-concurrency rejections that are otherwise only visible as an
 * exception at whichever layer happens to catch it. An append that is rolled back after a successful commit, because
 * another commit action of its {@link org.axonframework.messaging.core.unitofwork.ProcessingContext} failed, is not
 * reported.
 * <p>
 * The observer is invoked on the thread that completes the append stage it reports on, before the caller of that stage
 * continues. Implementations should therefore be fast and non-blocking, and must be thread-safe, as concurrent appends
 * are reported concurrently. An exception thrown by an observer is logged and otherwise ignored: it never changes the
 * outcome of the append. An {@link Error} does not change the outcome either; see
 * {@link AppendObservingEventStorageEngine} for how it reaches the caller.
 * <p>
 * Register an observer as a component, so that {@link AppendObservingEventStorageEngine#decoratorDefinition()} picks
 * it up:
 * <pre>{@code
 * EventSourcingConfigurer.create()
 *                        .componentRegistry(registry -> registry
 *                                .registerComponent(AppendObserver.class, config -> observation -> {
 *                                    if (observation.outcome() instanceof AppendObservation.Outcome.Rejected) {
 *                                        rejectionCounter.increment();
 *                                    }
 *                                })
 *                                .registerDecorator(AppendObservingEventStorageEngine.decoratorDefinition()));
 * }</pre>
 *
 * @author Alexey Afanasyev
 * @see AppendObservingEventStorageEngine
 * @see LoggingAppendObserver
 * @since 5.5.0
 */
@FunctionalInterface
public interface AppendObserver {

    /**
     * Observes the terminal outcome of a single append.
     * <p>
     * Invoked at most once per append. The given {@code observation} carries the {@link AppendCondition}, the events
     * and the {@link org.axonframework.messaging.core.unitofwork.ProcessingContext} of the append, together with its
     * {@link AppendObservation.Outcome outcome}.
     *
     * @param observation the outcome of an append, together with what was appended
     */
    void observe(AppendObservation observation);
}

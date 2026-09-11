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

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.test.AxonAssertionError;
import org.hamcrest.Matcher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Interface towards an object that contains the results of a Saga test fixture execution. Assertions are made against
 * the state of the Sagas and the messages they produced during the "when" phase.
 *
 * @author Allard Buijze
 * @since 1.1
 */
public interface FixtureExecutionResult {

    /**
     * Asserts that the repository contains the given {@code expected} amount of active Sagas.
     * <p>
     * Counts every Saga in the store, whatever its type, as Axon Framework 4 did. That makes this assertion asymmetric
     * with {@link #expectAssociationWith(String, Object)}, which does filter on the Saga type under test.
     *
     * @param expected the expected number of active Sagas in this fixture
     * @return the FixtureExecutionResult for method chaining
     * @throws AxonAssertionError when the store holds another number of Sagas
     */
    FixtureExecutionResult expectActiveSagas(int expected);

    /**
     * Asserts that at least one of the active Sagas is associated with the given {@code associationKey} and
     * {@code associationValue}.
     * <p>
     * The {@code associationValue} is compared by its {@link Object#toString() string representation}, as Axon
     * Framework 4 did.
     *
     * @param associationKey   the key of the association
     * @param associationValue the value of the association
     * @return the FixtureExecutionResult for method chaining
     * @throws AxonAssertionError when no Saga holds the association
     */
    FixtureExecutionResult expectAssociationWith(String associationKey, Object associationValue);

    /**
     * Asserts that none of the active Sagas is associated with the given {@code associationKey} and
     * {@code associationValue}.
     * <p>
     * The {@code associationValue} is compared by its {@link Object#toString() string representation}, as Axon
     * Framework 4 did.
     *
     * @param associationKey   the key of the association
     * @param associationValue the value of the association
     * @return the FixtureExecutionResult for method chaining
     * @throws AxonAssertionError when a Saga holds the association
     */
    FixtureExecutionResult expectNoAssociationWith(String associationKey, Object associationValue);

    /**
     * Asserts that the Sagas dispatched the given commands, in the exact sequence given.
     * <p>
     * Each element is either a {@link CommandMessage}, in which case payload and metadata are both compared, or a
     * payload, in which case only the payload is compared.
     *
     * @param commands the commands expected to have been dispatched
     * @return the FixtureExecutionResult for method chaining
     * @throws AxonAssertionError when another set of commands was dispatched
     */
    FixtureExecutionResult expectDispatchedCommands(Object... commands);

    /**
     * Asserts that the Sagas dispatched commands matching the given {@code matcher}.
     *
     * @param matcher the matcher validating the dispatched commands
     * @return the FixtureExecutionResult for method chaining
     * @throws AxonAssertionError when the dispatched commands do not match
     */
    FixtureExecutionResult expectDispatchedCommandsMatching(Matcher<? extends List<? super CommandMessage>> matcher);

    /**
     * Asserts that the Sagas dispatched no commands.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws AxonAssertionError when any command was dispatched
     */
    FixtureExecutionResult expectNoDispatchedCommands();

    /**
     * Asserts that the Sagas published the given events, in the exact sequence given.
     * <p>
     * Each element is either an {@link EventMessage} or a payload; a message is unwrapped to its payload, so only
     * payloads are compared and metadata is not, as in Axon Framework 4. The event that drove the "when" phase is not
     * part of this set, since the test published it rather than the Saga.
     *
     * @param expected the events expected to have been published
     * @return the FixtureExecutionResult for method chaining
     * @throws AxonAssertionError when another set of events was published
     */
    FixtureExecutionResult expectPublishedEvents(Object... expected);

    /**
     * Asserts that the Sagas published events matching the given {@code matcher}.
     * <p>
     * The event that drove the "when" phase is not part of the set the matcher sees, since the test published it
     * rather than the Saga.
     *
     * @param matcher the matcher validating the published events
     * @return the FixtureExecutionResult for method chaining
     * @throws AxonAssertionError when the published events do not match
     */
    FixtureExecutionResult expectPublishedEventsMatching(Matcher<? extends List<? super EventMessage>> matcher);

    /**
     * Asserts that the Saga handled the "when" event without failing.
     * <p>
     * Unlike Axon Framework 4, a failing {@code @SagaEventHandler} propagates by default rather than being logged and
     * swallowed, so this assertion holds unless the Saga threw. A Saga that suppresses its own failures with an
     * {@code @ExceptionHandler} passes it, as it did in Axon Framework 4.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws AxonAssertionError when handling the "when" event failed
     */
    FixtureExecutionResult expectSuccessfulHandlerExecution();

    // Deadlines and the event scheduler have not been ported into axon-legacy yet. The assertions below are declared
    // so an Axon Framework 4 test suite still compiles, but every call throws an UnsupportedOperationException instead
    // of passing without exercising the requested behaviour. The Axon Framework 4 implementation of each is kept as a
    // comment in FixtureExecutionResultImpl.
    //
    // TODO #5006 - not declared at all are the overloads taking a Matcher over a DeadlineMessage, plus expectDeadlinesMetMatching,
    // expectDeadlinesMet and expectTriggeredDeadlinesMatching, because DeadlineMessage itself is not ported:
    //
    //     FixtureExecutionResult expectScheduledDeadlineMatching(Duration duration, Matcher<? super DeadlineMessage> matcher);
    //     FixtureExecutionResult expectScheduledDeadlineMatching(Instant scheduledTime, Matcher<? super DeadlineMessage> matcher);
    //     FixtureExecutionResult expectNoScheduledDeadlineMatching(Matcher<? super DeadlineMessage> matcher);
    //     FixtureExecutionResult expectNoScheduledDeadlineMatching(Duration durationToScheduledTime, Matcher<? super DeadlineMessage> matcher);
    //     FixtureExecutionResult expectNoScheduledDeadlineMatching(Instant scheduledTime, Matcher<? super DeadlineMessage> matcher);
    //     FixtureExecutionResult expectNoScheduledDeadlineMatching(Instant from, Instant to, Matcher<? super DeadlineMessage> matcher);
    //     FixtureExecutionResult expectTriggeredDeadlinesMatching(Matcher<? extends List<? super DeadlineMessage>> matcher);
    //     @Deprecated FixtureExecutionResult expectDeadlinesMetMatching(Matcher<? extends List<? super DeadlineMessage>> matcher);
    //     @Deprecated FixtureExecutionResult expectDeadlinesMet(Object... expected);

    /**
     * Asserts that an event matching the given {@code matcher} is scheduled after the given {@code duration}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines are ported into {@code axon-legacy}
     */
    FixtureExecutionResult expectScheduledEventMatching(Duration duration, Matcher<? super EventMessage> matcher);

    /**
     * Asserts that the given {@code applicationEvent} is scheduled after the given {@code duration}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines are ported into {@code axon-legacy}
     */
    FixtureExecutionResult expectScheduledEvent(Duration duration, Object applicationEvent);

    /**
     * Asserts that an event of the given {@code eventType} is scheduled after the given {@code duration}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines are ported into {@code axon-legacy}
     */
    FixtureExecutionResult expectScheduledEventOfType(Duration duration, Class<?> eventType);

    /**
     * Asserts that an event matching the given {@code matcher} is scheduled at the given {@code scheduledTime}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines are ported into {@code axon-legacy}
     */
    FixtureExecutionResult expectScheduledEventMatching(Instant scheduledTime, Matcher<? super EventMessage> matcher);

    /**
     * Asserts that the given {@code applicationEvent} is scheduled at the given {@code scheduledTime}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines are ported into {@code axon-legacy}
     */
    FixtureExecutionResult expectScheduledEvent(Instant scheduledTime, Object applicationEvent);

    /**
     * Asserts that an event of the given {@code eventType} is scheduled at the given {@code scheduledTime}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines are ported into {@code axon-legacy}
     */
    FixtureExecutionResult expectScheduledEventOfType(Instant scheduledTime, Class<?> eventType);

    /**
     * Asserts that no events are scheduled.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines and event scheduling are ported into
     *                                       {@code axon-legacy}
     */
    FixtureExecutionResult expectNoScheduledEvents();

    /**
     * Asserts that no event matching the given {@code matcher} is scheduled after the given duration.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines and event scheduling are ported into
     *                                       {@code axon-legacy}
     */
    FixtureExecutionResult expectNoScheduledEventMatching(Duration durationToScheduledTime, Matcher<? super EventMessage> matcher);

    /**
     * Asserts that the given {@code event} is not scheduled after the given duration.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines and event scheduling are ported into
     *                                       {@code axon-legacy}
     */
    FixtureExecutionResult expectNoScheduledEvent(Duration durationToScheduledTime, Object event);

    /**
     * Asserts that no event of the given {@code eventType} is scheduled after the given duration.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines and event scheduling are ported into
     *                                       {@code axon-legacy}
     */
    FixtureExecutionResult expectNoScheduledEventOfType(Duration durationToScheduledTime, Class<?> eventType);

    /**
     * Asserts that no event matching the given {@code matcher} is scheduled at the given {@code scheduledTime}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines and event scheduling are ported into
     *                                       {@code axon-legacy}
     */
    FixtureExecutionResult expectNoScheduledEventMatching(Instant scheduledTime, Matcher<? super EventMessage> matcher);

    /**
     * Asserts that the given {@code event} is not scheduled at the given {@code scheduledTime}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines and event scheduling are ported into
     *                                       {@code axon-legacy}
     */
    FixtureExecutionResult expectNoScheduledEvent(Instant scheduledTime, Object event);

    /**
     * Asserts that no event of the given {@code eventType} is scheduled at the given {@code scheduledTime}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines and event scheduling are ported into
     *                                       {@code axon-legacy}
     */
    FixtureExecutionResult expectNoScheduledEventOfType(Instant scheduledTime, Class<?> eventType);

    /**
     * Asserts that the given {@code deadline} is scheduled after the given {@code duration}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines are ported into {@code axon-legacy}
     */
    FixtureExecutionResult expectScheduledDeadline(Duration duration, Object deadline);

    /**
     * Asserts that a deadline of the given {@code deadlineType} is scheduled after the given {@code duration}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines are ported into {@code axon-legacy}
     */
    FixtureExecutionResult expectScheduledDeadlineOfType(Duration duration, Class<?> deadlineType);

    /**
     * Asserts that a deadline with the given {@code deadlineName} is scheduled after the given {@code duration}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines are ported into {@code axon-legacy}
     */
    FixtureExecutionResult expectScheduledDeadlineWithName(Duration duration, String deadlineName);

    /**
     * Asserts that the given {@code deadline} is scheduled at the given {@code scheduledTime}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines are ported into {@code axon-legacy}
     */
    FixtureExecutionResult expectScheduledDeadline(Instant scheduledTime, Object deadline);

    /**
     * Asserts that a deadline of the given {@code deadlineType} is scheduled at the given {@code scheduledTime}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines are ported into {@code axon-legacy}
     */
    FixtureExecutionResult expectScheduledDeadlineOfType(Instant scheduledTime, Class<?> deadlineType);

    /**
     * Asserts that a deadline with the given {@code deadlineName} is scheduled at the given {@code scheduledTime}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines are ported into {@code axon-legacy}
     */
    FixtureExecutionResult expectScheduledDeadlineWithName(Instant scheduledTime, String deadlineName);

    /**
     * Asserts that no deadlines are scheduled.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines and event scheduling are ported into
     *                                       {@code axon-legacy}
     */
    FixtureExecutionResult expectNoScheduledDeadlines();

    /**
     * Asserts that the given {@code deadline} is not scheduled after the given duration.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines and event scheduling are ported into
     *                                       {@code axon-legacy}
     */
    FixtureExecutionResult expectNoScheduledDeadline(Duration durationToScheduledTime, Object deadline);

    /**
     * Asserts that no deadline of the given {@code deadlineType} is scheduled after the given duration.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines and event scheduling are ported into
     *                                       {@code axon-legacy}
     */
    FixtureExecutionResult expectNoScheduledDeadlineOfType(Duration durationToScheduledTime, Class<?> deadlineType);

    /**
     * Asserts that no deadline with the given {@code deadlineName} is scheduled after the given duration.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines and event scheduling are ported into
     *                                       {@code axon-legacy}
     */
    FixtureExecutionResult expectNoScheduledDeadlineWithName(Duration durationToScheduledTime, String deadlineName);

    /**
     * Asserts that the given {@code deadline} is not scheduled at the given {@code scheduledTime}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines and event scheduling are ported into
     *                                       {@code axon-legacy}
     */
    FixtureExecutionResult expectNoScheduledDeadline(Instant scheduledTime, Object deadline);

    /**
     * Asserts that no deadline of the given {@code deadlineType} is scheduled at the given {@code scheduledTime}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines and event scheduling are ported into
     *                                       {@code axon-legacy}
     */
    FixtureExecutionResult expectNoScheduledDeadlineOfType(Instant scheduledTime, Class<?> deadlineType);

    /**
     * Asserts that no deadline with the given {@code deadlineName} is scheduled at the given {@code scheduledTime}.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines and event scheduling are ported into
     *                                       {@code axon-legacy}
     */
    FixtureExecutionResult expectNoScheduledDeadlineWithName(Instant scheduledTime, String deadlineName);

    /**
     * Asserts that the given {@code deadline} is not scheduled between {@code from} and {@code to}, both inclusive.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines and event scheduling are ported into
     *                                       {@code axon-legacy}
     */
    FixtureExecutionResult expectNoScheduledDeadline(Instant from, Instant to, Object deadline);

    /**
     * Asserts that no deadline of the given {@code deadlineType} is scheduled between {@code from} and {@code to}, both inclusive.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines and event scheduling are ported into
     *                                       {@code axon-legacy}
     */
    FixtureExecutionResult expectNoScheduledDeadlineOfType(Instant from, Instant to, Class<?> deadlineType);

    /**
     * Asserts that no deadline with the given {@code deadlineName} is scheduled between {@code from} and {@code to}, both inclusive.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines and event scheduling are ported into
     *                                       {@code axon-legacy}
     */
    FixtureExecutionResult expectNoScheduledDeadlineWithName(Instant from, Instant to, String deadlineName);

    /**
     * Asserts that the given {@code expected} deadlines were triggered.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines are ported into {@code axon-legacy}
     */
    FixtureExecutionResult expectTriggeredDeadlines(Object... expected);

    /**
     * Asserts that deadlines with the given {@code expectedDeadlineNames} were triggered.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines are ported into {@code axon-legacy}
     */
    FixtureExecutionResult expectTriggeredDeadlinesWithName(String... expectedDeadlineNames);

    /**
     * Asserts that deadlines of the given {@code expectedDeadlineTypes} were triggered.
     *
     * @return the FixtureExecutionResult for method chaining
     * @throws UnsupportedOperationException always, until deadlines are ported into {@code axon-legacy}
     */
    FixtureExecutionResult expectTriggeredDeadlinesOfType(Class<?>... expectedDeadlineTypes);
}

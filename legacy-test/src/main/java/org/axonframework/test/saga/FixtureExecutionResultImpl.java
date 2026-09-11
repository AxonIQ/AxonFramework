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
import org.axonframework.test.fixture.AxonTestPhase;
import org.axonframework.test.matchers.FieldFilter;
import org.axonframework.test.matchers.Matchers;
import org.hamcrest.Matcher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default implementation of {@link FixtureExecutionResult}, asserting against the {@link AxonTestPhase.Then then-phase}
 * the "when" phase produced.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @since 5.4.0
 */
class FixtureExecutionResultImpl implements FixtureExecutionResult {

    private final Class<?> sagaType;
    private final AxonTestPhase.Then.Event then;
    private final CommandValidator commandValidator;
    private final EventValidator eventValidator;

    /**
     * Constructs a {@code FixtureExecutionResultImpl} asserting on the given {@code then} phase.
     *
     * @param sagaType    the type of Saga under test, used to filter the store on the association assertions
     * @param then        the then-phase of the fixture the Saga was driven through
     * @param fieldFilter the filter describing the fields to include when comparing messages
     */
    FixtureExecutionResultImpl(Class<?> sagaType,
                               AxonTestPhase.Then.Event then,
                               FieldFilter fieldFilter) {
        this.sagaType = Objects.requireNonNull(sagaType, "The sagaType may not be null.");
        this.then = Objects.requireNonNull(then, "The then-phase may not be null.");
        Objects.requireNonNull(fieldFilter, "The fieldFilter may not be null.");
        this.commandValidator = new CommandValidator(this::dispatchedCommands, fieldFilter);
        this.eventValidator = new EventValidator(this::publishedEvents, fieldFilter);
    }

    @Override
    public FixtureExecutionResult expectActiveSagas(int expected) {
        then.expect(SagaAssertions.activeSagas(expected));
        return this;
    }

    @Override
    public FixtureExecutionResult expectAssociationWith(String associationKey, Object associationValue) {
        then.expect(SagaAssertions.associationWith(sagaType, associationKey, associationValue));
        return this;
    }

    @Override
    public FixtureExecutionResult expectNoAssociationWith(String associationKey, Object associationValue) {
        then.expect(SagaAssertions.noAssociationWith(sagaType, associationKey, associationValue));
        return this;
    }

    @Override
    public FixtureExecutionResult expectDispatchedCommands(Object... commands) {
        commandValidator.assertDispatchedEqualTo(commands);
        return this;
    }

    @Override
    public FixtureExecutionResult expectDispatchedCommandsMatching(
            Matcher<? extends List<? super CommandMessage>> matcher
    ) {
        commandValidator.assertDispatchedMatching(matcher);
        return this;
    }

    @Override
    public FixtureExecutionResult expectNoDispatchedCommands() {
        commandValidator.assertDispatchedMatching(Matchers.noCommands());
        return this;
    }

    @Override
    public FixtureExecutionResult expectPublishedEvents(Object... expected) {
        eventValidator.assertPublishedEvents(expected);
        return this;
    }

    @Override
    public FixtureExecutionResult expectPublishedEventsMatching(
            Matcher<? extends List<? super EventMessage>> matcher
    ) {
        eventValidator.assertPublishedEventsMatching(matcher);
        return this;
    }

    @Override
    public FixtureExecutionResult expectSuccessfulHandlerExecution() {
        then.success();
        return this;
    }


    @Override
    public FixtureExecutionResult expectScheduledEventMatching(Duration duration, Matcher<? super EventMessage> matcher) {
        // TODO #5006 - Axon Framework 4:
        // eventSchedulerValidator.assertScheduledEventMatching(duration, matcher);
        // return this;
        throw NotPorted.deadlines("expectScheduledEventMatching");
    }

    @Override
    public FixtureExecutionResult expectScheduledEvent(Duration duration, Object applicationEvent) {
        // TODO #5006 - Axon Framework 4:
        // return expectScheduledEventMatching(duration, messageWithPayload(deepEquals(applicationEvent, fieldFilter)));
        throw NotPorted.deadlines("expectScheduledEvent");
    }

    @Override
    public FixtureExecutionResult expectScheduledEventOfType(Duration duration, Class<?> eventType) {
        // TODO #5006 - Axon Framework 4:
        // return expectScheduledEventMatching(duration, messageWithPayload(any(eventType)));
        throw NotPorted.deadlines("expectScheduledEventOfType");
    }

    @Override
    public FixtureExecutionResult expectScheduledEventMatching(Instant scheduledTime, Matcher<? super EventMessage> matcher) {
        // TODO #5006 - Axon Framework 4:
        // eventSchedulerValidator.assertScheduledEventMatching(scheduledTime, matcher);
        // return this;
        throw NotPorted.deadlines("expectScheduledEventMatching");
    }

    @Override
    public FixtureExecutionResult expectScheduledEvent(Instant scheduledTime, Object applicationEvent) {
        // TODO #5006 - Axon Framework 4:
        // return expectScheduledEventMatching(scheduledTime,
        //                                     messageWithPayload(deepEquals(applicationEvent, fieldFilter)));
        throw NotPorted.deadlines("expectScheduledEvent");
    }

    @Override
    public FixtureExecutionResult expectScheduledEventOfType(Instant scheduledTime, Class<?> eventType) {
        // TODO #5006 - Axon Framework 4:
        // return expectScheduledEventMatching(scheduledTime, messageWithPayload(any(eventType)));
        throw NotPorted.deadlines("expectScheduledEventOfType");
    }

    @Override
    public FixtureExecutionResult expectNoScheduledEvents() {
        // TODO #5006 - Axon Framework 4:
        // eventSchedulerValidator.assertNoScheduledEvents();
        // return this;
        throw NotPorted.deadlines("expectNoScheduledEvents");
    }

    @Override
    public FixtureExecutionResult expectNoScheduledEventMatching(Duration durationToScheduledTime, Matcher<? super EventMessage> matcher) {
        // TODO #5006 - Axon Framework 4:
        // eventSchedulerValidator.assertNoScheduledEventMatching(durationToScheduledTime, matcher);
        // return this;
        throw NotPorted.deadlines("expectNoScheduledEventMatching");
    }

    @Override
    public FixtureExecutionResult expectNoScheduledEvent(Duration durationToScheduledTime, Object event) {
        // TODO #5006 - Axon Framework 4:
        // return expectNoScheduledEventMatching(durationToScheduledTime,
        //                                       messageWithPayload(deepEquals(event, fieldFilter)));
        throw NotPorted.deadlines("expectNoScheduledEvent");
    }

    @Override
    public FixtureExecutionResult expectNoScheduledEventOfType(Duration durationToScheduledTime, Class<?> eventType) {
        // TODO #5006 - Axon Framework 4:
        // return expectNoScheduledEventMatching(durationToScheduledTime, messageWithPayload(any(eventType)));
        throw NotPorted.deadlines("expectNoScheduledEventOfType");
    }

    @Override
    public FixtureExecutionResult expectNoScheduledEventMatching(Instant scheduledTime, Matcher<? super EventMessage> matcher) {
        // TODO #5006 - Axon Framework 4:
        // eventSchedulerValidator.assertNoScheduledEventMatching(scheduledTime, matcher);
        // return this;
        throw NotPorted.deadlines("expectNoScheduledEventMatching");
    }

    @Override
    public FixtureExecutionResult expectNoScheduledEvent(Instant scheduledTime, Object event) {
        // TODO #5006 - Axon Framework 4:
        // return expectNoScheduledEventMatching(scheduledTime, messageWithPayload(deepEquals(event, fieldFilter)));
        throw NotPorted.deadlines("expectNoScheduledEvent");
    }

    @Override
    public FixtureExecutionResult expectNoScheduledEventOfType(Instant scheduledTime, Class<?> eventType) {
        // TODO #5006 - Axon Framework 4:
        // return expectNoScheduledEventMatching(scheduledTime, messageWithPayload(any(eventType)));
        throw NotPorted.deadlines("expectNoScheduledEventOfType");
    }

    @Override
    public FixtureExecutionResult expectScheduledDeadline(Duration duration, Object deadline) {
        // TODO #5006 - Axon Framework 4:
        // return expectScheduledDeadlineMatching(duration, messageWithPayload(deepEquals(deadline, fieldFilter)));
        throw NotPorted.deadlines("expectScheduledDeadline");
    }

    @Override
    public FixtureExecutionResult expectScheduledDeadlineOfType(Duration duration, Class<?> deadlineType) {
        // TODO #5006 - Axon Framework 4:
        // return expectScheduledDeadlineMatching(duration, messageWithPayload(any(deadlineType)));
        throw NotPorted.deadlines("expectScheduledDeadlineOfType");
    }

    @Override
    public FixtureExecutionResult expectScheduledDeadlineWithName(Duration duration, String deadlineName) {
        // TODO #5006 - Axon Framework 4:
        // return expectScheduledDeadlineMatching(
        //         duration,
        //         matches(deadlineMessage -> deadlineMessage.getDeadlineName().equals(deadlineName))
        // );
        throw NotPorted.deadlines("expectScheduledDeadlineWithName");
    }

    @Override
    public FixtureExecutionResult expectScheduledDeadline(Instant scheduledTime, Object deadline) {
        // TODO #5006 - Axon Framework 4:
        // return expectScheduledDeadlineMatching(scheduledTime, messageWithPayload(deepEquals(deadline, fieldFilter)));
        throw NotPorted.deadlines("expectScheduledDeadline");
    }

    @Override
    public FixtureExecutionResult expectScheduledDeadlineOfType(Instant scheduledTime, Class<?> deadlineType) {
        // TODO #5006 - Axon Framework 4:
        // return expectScheduledDeadlineMatching(scheduledTime, messageWithPayload(any(deadlineType)));
        throw NotPorted.deadlines("expectScheduledDeadlineOfType");
    }

    @Override
    public FixtureExecutionResult expectScheduledDeadlineWithName(Instant scheduledTime, String deadlineName) {
        // TODO #5006 - Axon Framework 4:
        // return expectScheduledDeadlineMatching(
        //         scheduledTime,
        //         matches(deadlineMessage -> deadlineMessage.getDeadlineName().equals(deadlineName))
        // );
        throw NotPorted.deadlines("expectScheduledDeadlineWithName");
    }

    @Override
    public FixtureExecutionResult expectNoScheduledDeadlines() {
        // TODO #5006 - Axon Framework 4:
        // deadlineManagerValidator.assertNoScheduledDeadlines();
        // return this;
        throw NotPorted.deadlines("expectNoScheduledDeadlines");
    }

    @Override
    public FixtureExecutionResult expectNoScheduledDeadline(Duration durationToScheduledTime, Object deadline) {
        // TODO #5006 - Axon Framework 4:
        // return expectNoScheduledDeadlineMatching(durationToScheduledTime,
        //                                          messageWithPayload(deepEquals(deadline, fieldFilter)));
        throw NotPorted.deadlines("expectNoScheduledDeadline");
    }

    @Override
    public FixtureExecutionResult expectNoScheduledDeadlineOfType(Duration durationToScheduledTime, Class<?> deadlineType) {
        // TODO #5006 - Axon Framework 4:
        // return expectNoScheduledDeadlineMatching(durationToScheduledTime, messageWithPayload(any(deadlineType)));
        throw NotPorted.deadlines("expectNoScheduledDeadlineOfType");
    }

    @Override
    public FixtureExecutionResult expectNoScheduledDeadlineWithName(Duration durationToScheduledTime, String deadlineName) {
        // TODO #5006 - Axon Framework 4:
        // return expectNoScheduledDeadlineMatching(
        //         durationToScheduledTime,
        //         matches(deadlineMessage -> deadlineMessage.getDeadlineName().equals(deadlineName))
        // );
        throw NotPorted.deadlines("expectNoScheduledDeadlineWithName");
    }

    @Override
    public FixtureExecutionResult expectNoScheduledDeadline(Instant scheduledTime, Object deadline) {
        // TODO #5006 - Axon Framework 4:
        // return expectNoScheduledDeadlineMatching(scheduledTime,
        //                                          messageWithPayload(deepEquals(deadline, fieldFilter)));
        throw NotPorted.deadlines("expectNoScheduledDeadline");
    }

    @Override
    public FixtureExecutionResult expectNoScheduledDeadlineOfType(Instant scheduledTime, Class<?> deadlineType) {
        // TODO #5006 - Axon Framework 4:
        // return expectNoScheduledDeadlineMatching(scheduledTime, messageWithPayload(any(deadlineType)));
        throw NotPorted.deadlines("expectNoScheduledDeadlineOfType");
    }

    @Override
    public FixtureExecutionResult expectNoScheduledDeadlineWithName(Instant scheduledTime, String deadlineName) {
        // TODO #5006 - Axon Framework 4:
        // return expectNoScheduledDeadlineMatching(
        //         scheduledTime,
        //         matches(deadlineMessage -> deadlineMessage.getDeadlineName().equals(deadlineName))
        // );
        throw NotPorted.deadlines("expectNoScheduledDeadlineWithName");
    }

    @Override
    public FixtureExecutionResult expectNoScheduledDeadline(Instant from, Instant to, Object deadline) {
        // TODO #5006 - Axon Framework 4:
        // return expectNoScheduledDeadlineMatching(from, to, messageWithPayload(deepEquals(deadline, fieldFilter)));
        throw NotPorted.deadlines("expectNoScheduledDeadline");
    }

    @Override
    public FixtureExecutionResult expectNoScheduledDeadlineOfType(Instant from, Instant to, Class<?> deadlineType) {
        // TODO #5006 - Axon Framework 4:
        // return expectNoScheduledDeadlineMatching(from, to, messageWithPayload(any(deadlineType)));
        throw NotPorted.deadlines("expectNoScheduledDeadlineOfType");
    }

    @Override
    public FixtureExecutionResult expectNoScheduledDeadlineWithName(Instant from, Instant to, String deadlineName) {
        // TODO #5006 - Axon Framework 4:
        // return expectNoScheduledDeadlineMatching(
        //         from, to, matches(deadlineMessage -> deadlineMessage.getDeadlineName().equals(deadlineName)));
        throw NotPorted.deadlines("expectNoScheduledDeadlineWithName");
    }

    @Override
    public FixtureExecutionResult expectTriggeredDeadlines(Object... expected) {
        // TODO #5006 - Axon Framework 4:
        // deadlineManagerValidator.assertTriggeredDeadlines(expected);
        // return this;
        throw NotPorted.deadlines("expectTriggeredDeadlines");
    }

    @Override
    public FixtureExecutionResult expectTriggeredDeadlinesWithName(String... expectedDeadlineNames) {
        // TODO #5006 - Axon Framework 4:
        // deadlineManagerValidator.assertTriggeredDeadlinesWithName(expectedDeadlineNames);
        // return this;
        throw NotPorted.deadlines("expectTriggeredDeadlinesWithName");
    }

    @Override
    public FixtureExecutionResult expectTriggeredDeadlinesOfType(Class<?>... expectedDeadlineTypes) {
        // TODO #5006 - Axon Framework 4:
        // deadlineManagerValidator.assertTriggeredDeadlinesOfType(expectedDeadlineTypes);
        // return this;
        throw NotPorted.deadlines("expectTriggeredDeadlinesOfType");
    }

    private List<CommandMessage> dispatchedCommands() {
        AtomicReference<List<CommandMessage>> captured = new AtomicReference<>();
        then.commandsSatisfy(captured::set);
        return captured.get();
    }

    private List<EventMessage> publishedEvents() {
        AtomicReference<List<EventMessage>> captured = new AtomicReference<>();
        then.eventsSatisfy(captured::set);
        return captured.get();
    }
}

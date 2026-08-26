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
import org.axonframework.common.Assert;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.axonframework.test.AxonAssertionError;
import org.axonframework.test.fixture.CommandValidator;
import org.axonframework.test.matchers.FieldFilter;
import org.axonframework.test.matchers.Matchers;
import org.axonframework.test.util.RecordingCommandBus;
import org.hamcrest.Matcher;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Default implementation of FixtureExecutionResult.
 *
 * @author Allard Buijze
 * @since 1.1
 */
public class FixtureExecutionResultImpl<T> implements FixtureExecutionResult {

    private final RepositoryContentValidator<T> repositoryContentValidator;
    private final EventValidator eventValidator;
//    private final EventSchedulerValidator eventSchedulerValidator;
//    private final DeadlineManagerValidator deadlineManagerValidator;
    private final CommandValidator commandValidator;
    private final FieldFilter fieldFilter;
    private final List<Runnable> onStartRecordingCallbacks;
    private final RecordingListenerInvocationErrorHandler recordingListenerInvocationErrorHandler;

    /**
     * Initializes an instance and make it monitor the given infrastructure classes.
     *
     * @param sagaStore                               The SagaStore to monitor
//     * @param eventScheduler                          The scheduler to monitor
//     * @param deadlineManager                         The deadline manager to monitor
     * @param eventBus                                The event bus to monitor
     * @param commandBus                              The command bus to monitor
     * @param sagaType                                The type of Saga under test
     * @param fieldFilter                             The FieldFilter describing the fields to include in equality checks
     * @param recordingListenerInvocationErrorHandler The RecordingListenerInvocationErrorHandler to monitor
     */
    FixtureExecutionResultImpl(InMemorySagaStore sagaStore,
//                               StubEventScheduler eventScheduler,
//                               StubDeadlineManager deadlineManager,
                               EventBus eventBus,
                               RecordingCommandBus commandBus,
                               Class<T> sagaType,
                               FieldFilter fieldFilter,
                               RecordingListenerInvocationErrorHandler recordingListenerInvocationErrorHandler) {
        assertNotNull(recordingListenerInvocationErrorHandler, "recordingListenerInvocationErrorHandler cannot be null");
        this.fieldFilter = fieldFilter;
        this.recordingListenerInvocationErrorHandler = recordingListenerInvocationErrorHandler;
        commandValidator = new CommandValidator(commandBus, fieldFilter);
        repositoryContentValidator = new RepositoryContentValidator<>(sagaType, sagaStore);
        eventValidator = new EventValidator(eventBus, fieldFilter);
//        eventSchedulerValidator = new EventSchedulerValidator(eventScheduler);
//        deadlineManagerValidator = new DeadlineManagerValidator(deadlineManager, fieldFilter);
        onStartRecordingCallbacks = new CopyOnWriteArrayList<>();
    }

    /**
     * Registers a callback to be invoked when the fixture execution starts recording. This happens right before
     * invocation of the 'when' step (stimulus) of the fixture.
     * <p/>
     * Use this to manage Saga dependencies which are not an Axon first class citizen, but do require monitoring of
     * their interactions. For example, register the callback to set a mock in recording mode.
     *
     * @param onStartRecordingCallback callback to invoke
     */
    public void registerStartRecordingCallback(Runnable onStartRecordingCallback) {
        Assert.notNull(onStartRecordingCallback, () -> "onStartRecordingCallback may not be null");
        onStartRecordingCallbacks.add(onStartRecordingCallback);
    }

    /**
     * Tells this class to start monitoring activity in infrastructure classes.
     */
    public void startRecording() {
        eventValidator.startRecording();
        commandValidator.startRecording();
        recordingListenerInvocationErrorHandler.startRecording();
        onStartRecordingCallbacks.forEach(Runnable::run);
    }

    @Override
    public FixtureExecutionResult expectActiveSagas(int expected) {
        repositoryContentValidator.assertActiveSagas(expected);
        return this;
    }

    @Override
    public FixtureExecutionResult expectAssociationWith(String associationKey, Object associationValue) {
        repositoryContentValidator.assertAssociationPresent(associationKey, associationValue.toString());
        return this;
    }

    @Override
    public FixtureExecutionResult expectNoAssociationWith(String associationKey, Object associationValue) {
        repositoryContentValidator.assertNoAssociationPresent(associationKey, associationValue.toString());
        return this;
    }


//    @Override
//    public FixtureExecutionResult expectScheduledEventMatching(Duration duration,
//                                                               Matcher<? super EventMessage> matcher) {
//        eventSchedulerValidator.assertScheduledEventMatching(duration, matcher);
//        return this;
//    }

//    @Override
//    public FixtureExecutionResult expectScheduledDeadlineMatching(Duration duration,
//                                                                  Matcher<? super DeadlineMessage> matcher) {
//        deadlineManagerValidator.assertScheduledDeadlineMatching(duration, matcher);
//        return this;
//    }

//    @Override
//    public FixtureExecutionResult expectScheduledEvent(Duration duration, Object applicationEvent) {
//        return expectScheduledEventMatching(duration, messageWithPayload(deepEquals(applicationEvent, fieldFilter)));
//    }

//    @Override
//    public FixtureExecutionResult expectScheduledDeadline(Duration duration, Object deadline) {
//        return expectScheduledDeadlineMatching(duration, messageWithPayload(deepEquals(deadline, fieldFilter)));
//    }

//    @Override
//    public FixtureExecutionResult expectScheduledEventOfType(Duration duration, Class<?> eventType) {
//        return expectScheduledEventMatching(duration, messageWithPayload(any(eventType)));
//    }

//    @Override
//    public FixtureExecutionResult expectScheduledDeadlineOfType(Duration duration, Class<?> deadlineType) {
//        return expectScheduledDeadlineMatching(duration, messageWithPayload(any(deadlineType)));
//    }

//    @Override
//    public FixtureExecutionResult expectScheduledDeadlineWithName(Duration duration, String deadlineName) {
//        return expectScheduledDeadlineMatching(
//                duration,
//                matches(deadlineMessage -> deadlineMessage.getDeadlineName().equals(deadlineName))
//        );
//    }

//    @Override
//    public FixtureExecutionResult expectScheduledEventMatching(Instant scheduledTime,
//                                                               Matcher<? super EventMessage> matcher) {
//        eventSchedulerValidator.assertScheduledEventMatching(scheduledTime, matcher);
//        return this;
//    }

//    @Override
//    public FixtureExecutionResult expectScheduledDeadlineMatching(Instant scheduledTime,
//                                                                  Matcher<? super DeadlineMessage> matcher) {
//        deadlineManagerValidator.assertScheduledDeadlineMatching(scheduledTime, matcher);
//        return this;
//    }

//    @Override
//    public FixtureExecutionResult expectScheduledEvent(Instant scheduledTime, Object applicationEvent) {
//        return expectScheduledEventMatching(scheduledTime,
//                                            messageWithPayload(deepEquals(applicationEvent, fieldFilter)));
//    }

//    @Override
//    public FixtureExecutionResult expectScheduledDeadline(Instant scheduledTime, Object deadline) {
//        return expectScheduledDeadlineMatching(scheduledTime, messageWithPayload(deepEquals(deadline, fieldFilter)));
//    }

//    @Override
//    public FixtureExecutionResult expectScheduledEventOfType(Instant scheduledTime, Class<?> eventType) {
//        return expectScheduledEventMatching(scheduledTime, messageWithPayload(any(eventType)));
//    }

//    @Override
//    public FixtureExecutionResult expectScheduledDeadlineOfType(Instant scheduledTime, Class<?> deadlineType) {
//        return expectScheduledDeadlineMatching(scheduledTime, messageWithPayload(any(deadlineType)));
//    }

//    @Override
//    public FixtureExecutionResult expectScheduledDeadlineWithName(Instant scheduledTime, String deadlineName) {
//        return expectScheduledDeadlineMatching(
//                scheduledTime,
//                matches(deadlineMessage -> deadlineMessage.getDeadlineName().equals(deadlineName))
//        );
//    }

    @Override
    public FixtureExecutionResult expectDispatchedCommands(Object... expected) {
        commandValidator.assertDispatchedEqualTo(expected);
        return this;
    }

    @Override
    public FixtureExecutionResult expectDispatchedCommandsMatching(
            Matcher<? extends List<? super CommandMessage>> matcher) {
        commandValidator.assertDispatchedMatching(matcher);
        return this;
    }

    @Override
    public FixtureExecutionResult expectNoDispatchedCommands() {
        commandValidator.assertDispatchedMatching(Matchers.noCommands());
        return this;
    }

    @Override
    public FixtureExecutionResult expectPublishedEventsMatching(Matcher<? extends List<? super EventMessage>> matcher) {
        return null;
    }

    @Override
    public FixtureExecutionResult expectPublishedEvents(Object... expected) {
        return null;
    }

//    @Override
//    public FixtureExecutionResult expectNoScheduledEvents() {
//        eventSchedulerValidator.assertNoScheduledEvents();
//        return this;
//    }

//    @Override
//    public FixtureExecutionResult expectNoScheduledEventMatching(Duration durationToScheduledTime,
//                                                                 Matcher<? super EventMessage> matcher) {
//        eventSchedulerValidator.assertNoScheduledEventMatching(durationToScheduledTime, matcher);
//        return this;
//    }

//    @Override
//    public FixtureExecutionResult expectNoScheduledEvent(Duration durationToScheduledTime, Object event) {
//        return expectNoScheduledEventMatching(durationToScheduledTime,
//                                              messageWithPayload(deepEquals(event, fieldFilter)));
//    }

//    @Override
//    public FixtureExecutionResult expectNoScheduledEventOfType(Duration durationToScheduledTime, Class<?> eventType) {
//        return expectNoScheduledEventMatching(durationToScheduledTime, messageWithPayload(any(eventType)));
//    }

//    @Override
//    public FixtureExecutionResult expectNoScheduledEventMatching(Instant scheduledTime,
//                                                                 Matcher<? super EventMessage> matcher) {
//        eventSchedulerValidator.assertNoScheduledEventMatching(scheduledTime, matcher);
//        return this;
//    }

//    @Override
//    public FixtureExecutionResult expectNoScheduledEvent(Instant scheduledTime, Object event) {
//        return expectNoScheduledEventMatching(scheduledTime, messageWithPayload(deepEquals(event, fieldFilter)));
//    }
//
//    @Override
//    public FixtureExecutionResult expectNoScheduledEventOfType(Instant scheduledTime, Class<?> eventType) {
//        return expectNoScheduledEventMatching(scheduledTime, messageWithPayload(any(eventType)));
//    }
//
//    @Override
//    public FixtureExecutionResult expectNoScheduledDeadlines() {
//        deadlineManagerValidator.assertNoScheduledDeadlines();
//        return this;
//    }

//    @Override
//    public FixtureExecutionResult expectNoScheduledDeadlineMatching(Matcher<? super DeadlineMessage> matcher) {
//        deadlineManagerValidator.assertNoScheduledDeadlineMatching(matcher);
//        return this;
//    }
//
//    @Override
//    public FixtureExecutionResult expectNoScheduledDeadlineMatching(Duration durationToScheduledTime,
//                                                                    Matcher<? super DeadlineMessage> matcher) {
//        Instant scheduledTime = deadlineManagerValidator.currentDateTime().plus(durationToScheduledTime);
//        return expectNoScheduledDeadlineMatching(scheduledTime, matcher);
//    }
//
//    @Override
//    public FixtureExecutionResult expectNoScheduledDeadline(Duration durationToScheduledTime, Object deadline) {
//        return expectNoScheduledDeadlineMatching(durationToScheduledTime,
//                                                 messageWithPayload(deepEquals(deadline, fieldFilter)));
//    }
//
//    @Override
//    public FixtureExecutionResult expectNoScheduledDeadlineOfType(Duration durationToScheduledTime,
//                                                                  Class<?> deadlineType) {
//        return expectNoScheduledDeadlineMatching(durationToScheduledTime, messageWithPayload(any(deadlineType)));
//    }
//
//    @Override
//    public FixtureExecutionResult expectNoScheduledDeadlineWithName(Duration durationToScheduledTime,
//                                                                    String deadlineName) {
//        return expectNoScheduledDeadlineMatching(
//                durationToScheduledTime,
//                matches(deadlineMessage -> deadlineMessage.getDeadlineName().equals(deadlineName))
//        );
//    }
//
//    @Override
//    public FixtureExecutionResult expectNoScheduledDeadlineMatching(Instant scheduledTime,
//                                                                    Matcher<? super DeadlineMessage> matcher) {
//        return expectNoScheduledDeadlineMatching(matches(
//                deadlineMessage -> deadlineMessage.timestamp().equals(scheduledTime)
//                        && matcher.matches(deadlineMessage)
//        ));
//    }
//
//    @Override
//    public FixtureExecutionResult expectNoScheduledDeadline(Instant scheduledTime, Object deadline) {
//        return expectNoScheduledDeadlineMatching(scheduledTime, messageWithPayload(deepEquals(deadline, fieldFilter)));
//    }
//
//    @Override
//    public FixtureExecutionResult expectNoScheduledDeadlineOfType(Instant scheduledTime, Class<?> deadlineType) {
//        return expectNoScheduledDeadlineMatching(scheduledTime, messageWithPayload(any(deadlineType)));
//    }
//
//    @Override
//    public FixtureExecutionResult expectNoScheduledDeadlineWithName(Instant scheduledTime, String deadlineName) {
//        return expectNoScheduledDeadlineMatching(
//                scheduledTime,
//                matches(deadlineMessage -> deadlineMessage.getDeadlineName().equals(deadlineName))
//        );
//    }
//
//    @Override
//    public FixtureExecutionResult expectNoScheduledDeadlineMatching(Instant from, Instant to, Matcher<? super DeadlineMessage> matcher) {
//        return expectNoScheduledDeadlineMatching(matches(
//                deadlineMessage -> !(deadlineMessage.timestamp().isBefore(from) || deadlineMessage.timestamp().isAfter(to))
//                        && matcher.matches(deadlineMessage)
//        ));
//    }
//
//    @Override
//    public FixtureExecutionResult expectNoScheduledDeadline(Instant from, Instant to, Object deadline) {
//        return expectNoScheduledDeadlineMatching(from, to, messageWithPayload(deepEquals(deadline, fieldFilter)));
//    }
//
//    @Override
//    public FixtureExecutionResult expectNoScheduledDeadlineOfType(Instant from, Instant to, Class<?> deadlineType) {
//        return expectNoScheduledDeadlineMatching(from, to, messageWithPayload(any(deadlineType)));
//    }
//
//    @Override
//    public FixtureExecutionResult expectNoScheduledDeadlineWithName(Instant from, Instant to, String deadlineName) {
//        return expectNoScheduledDeadlineMatching(from, to, matches(deadlineMessage -> deadlineMessage.getDeadlineName().equals(deadlineName)));
//    }
//
//    @Override
//    public FixtureExecutionResult expectPublishedEventsMatching(
//            Matcher<? extends List<? super EventMessage>> matcher) {
//        eventValidator.assertPublishedEventsMatching(matcher);
//        return this;
//    }
//
//    @Override
//    public FixtureExecutionResult expectTriggeredDeadlinesMatching(
//            Matcher<? extends List<? super DeadlineMessage>> matcher
//    ) {
//        deadlineManagerValidator.assertTriggeredDeadlinesMatching(matcher);
//        return this;
//    }
//
//    @Override
//    public FixtureExecutionResult expectPublishedEvents(Object... expected) {
//        eventValidator.assertPublishedEvents(expected);
//        return this;
//    }
//
//    @Override
//    public FixtureExecutionResult expectTriggeredDeadlines(Object... expected) {
//        deadlineManagerValidator.assertTriggeredDeadlines(expected);
//        return this;
//    }
//
//    @Override
//    public FixtureExecutionResult expectTriggeredDeadlinesWithName(String... expectedDeadlineNames) {
//        deadlineManagerValidator.assertTriggeredDeadlinesWithName(expectedDeadlineNames);
//        return this;
//    }

//    @Override
//    public FixtureExecutionResult expectTriggeredDeadlinesOfType(Class<?>... expectedDeadlineTypes) {
//        deadlineManagerValidator.assertTriggeredDeadlinesOfType(expectedDeadlineTypes);
//        return this;
//    }

    @Override
    public FixtureExecutionResult expectSuccessfulHandlerExecution() {
        Optional<Exception> throwableOptional = recordingListenerInvocationErrorHandler.getException();
        if (throwableOptional.isPresent()) {
            throw new AxonAssertionError(String.format("An exception occurred during event handling: [%s]",
                                                       throwableOptional.get().getMessage()));
        }
        return this;
    }

}

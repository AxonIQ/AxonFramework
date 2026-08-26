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

package org.axonframework.test.deadline;

import org.axonframework.deadline.DeadlineMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.test.AxonAssertionError;
import org.axonframework.test.matchers.FieldFilter;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.StringDescription;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static org.axonframework.common.DateTimeUtils.formatInstant;
import static org.axonframework.test.matchers.Matchers.*;
import static org.hamcrest.Matchers.any;

/**
 * Helps validation of deadline mechanism provided by {@link StubDeadlineManager}.
 *
 * @author Milan Savic
 * @since 3.3
 */
public class DeadlineManagerValidator {

    private final StubDeadlineManager deadlineManager;
    private final FieldFilter fieldFilter;

    /**
     * Instantiates this validator with {@code deadlineManager} which is used for validation purposes and {@code
     * fieldFilter}.
     *
     * @param deadlineManager Stub implementation of deadline manager which keeps track of scheduled and met deadlines
     * @param fieldFilter     Indicates whether any given Field should be accepted for processing or not
     */
    public DeadlineManagerValidator(StubDeadlineManager deadlineManager,
                                    FieldFilter fieldFilter) {
        this.deadlineManager = deadlineManager;
        this.fieldFilter = fieldFilter;
    }

    /**
     * The current date time as stated by the configured {@link StubDeadlineManager}.
     *
     * @return current date time as stated by the configured {@link StubDeadlineManager}
     */
    public Instant currentDateTime() {
        return deadlineManager.getCurrentDateTime();
    }

    /**
     * Asserts that a deadline scheduled after given {@code duration} matches the given {@code matcher}.
     *
     * @param duration the delay expected before the deadline is met
     * @param matcher  the matcher that must match with the deadline scheduled at the given time
     */
    public void assertScheduledDeadlineMatching(Duration duration, Matcher<?> matcher) {
        Instant targetTime = currentDateTime().plus(duration);
        assertScheduledDeadlineMatching(targetTime, matcher);
    }

    /**
     * Asserts that a deadline scheduled at the given {@code scheduledTime} matches the given {@code matcher}.
     *
     * @param scheduledTime the time at which the deadline should be met
     * @param matcher       the matcher that must match with the deadline scheduled at the given time
     */
    public void assertScheduledDeadlineMatching(Instant scheduledTime, Matcher<?> matcher) {
        List<ScheduledDeadlineInfo> scheduledDeadlines = deadlineManager.getScheduledDeadlines();
        for (ScheduledDeadlineInfo scheduledDeadline : scheduledDeadlines) {
            if (scheduledDeadline.getScheduleTime().equals(scheduledTime) &&
                    matcher.matches(scheduledDeadline.deadlineMessage())) {
                return;
            }
        }
        Description expected = new StringDescription();
        Description actual = new StringDescription();
        matcher.describeTo(expected);
        describe(scheduledDeadlines, actual);
        throw new AxonAssertionError(format(
                "Did not find a deadline at the given deadline manager.\n"
                        + "Expected <%s> at <%s>,\n but got <%s>.\n",
                expected, scheduledTime, actual
        ));
    }

    /**
     * Asserts that <b>no</b> deadline matching the given {@code matcher} has been scheduled.
     *
     * @param matcher the matcher defining the deadline which should not be scheduled
     */
    public void assertNoScheduledDeadlineMatching(Matcher<?> matcher) {
        for (ScheduledDeadlineInfo scheduledDeadline : deadlineManager.getScheduledDeadlines()) {
            if (matcher.matches(scheduledDeadline.deadlineMessage())) {
                Description unexpected = new StringDescription();
                matcher.describeTo(unexpected);
                throw new AxonAssertionError(format(
                        "Unexpected matching deadline found at the given deadline manager.\nGot <%s>.\n",
                        unexpected
                ));
            }
        }
    }

    /**
     * Asserts that the triggered deadlines match the given {@code matcher}.
     *
     * @param matcher the matcher that will validate the actual deadlines
     */
    public void assertTriggeredDeadlinesMatching(Matcher<? extends Iterable<?>> matcher) {
        List<ScheduledDeadlineInfo> triggeredDeadlines = deadlineManager.getTriggeredDeadlines();
        List<DeadlineMessage> deadlineMessages = triggeredDeadlines.stream()
                                                                   .map(ScheduledDeadlineInfo::deadlineMessage)
                                                                   .collect(Collectors.toList());
        if (!matcher.matches(deadlineMessages)) {
            Description expected = new StringDescription();
            Description actual = new StringDescription();
            matcher.describeTo(expected);
            describe(triggeredDeadlines, actual);
            throw new AxonAssertionError(format(
                    "Expected deadlines were not triggered at the given deadline manager.\n"
                            + "Expected <%s>,\n but got <%s>.\n",
                    expected, actual
            ));
        }
    }

    /**
     * Asserts that the given {@code expected} deadlines have been triggered.
     *
     * @param expected the deadlines that must have been triggered
     */
    public void assertTriggeredDeadlines(Object... expected) {
        assertNumberOfAndMatchTriggeredDeadlines(
                expected.length,
                payloadsMatching(exactSequenceOf(createEqualToMatchers(expected)))
        );
    }

    /**
     * Asserts that the given {@code expectedDeadlineNames} have been triggered. Matches the complete and exact sequence
     * of the given names with the result of {@link StubDeadlineManager#getTriggeredDeadlines()}.
     *
     * @param expectedDeadlineNames the deadline names that must have been triggered
     */
    public void assertTriggeredDeadlinesWithName(String... expectedDeadlineNames) {
        assertNumberOfAndMatchTriggeredDeadlines(
                expectedDeadlineNames.length,
                exactSequenceOf(createDeadlineNameMatchers(expectedDeadlineNames))
        );
    }

    private Matcher<DeadlineMessage>[] createDeadlineNameMatchers(String[] expectedDeadlineNames) {
        List<Matcher<DeadlineMessage>> matchers = new ArrayList<>(expectedDeadlineNames.length);
        for (String deadlineName : expectedDeadlineNames) {
            matchers.add(matches(deadlineMessage -> deadlineMessage.getDeadlineName().equals(deadlineName)));
        }
        //noinspection unchecked
        return matchers.toArray(new Matcher[0]);
    }

    /**
     * Asserts that the given {@code expectedDeadlineTypes} have been triggered. Matches the complete and exact sequence
     * of the given types with the result of {@link StubDeadlineManager#getTriggeredDeadlines()}.
     *
     * @param expectedDeadlineTypes the deadline types that must have been triggered
     */
    public void assertTriggeredDeadlinesOfType(Class<?>... expectedDeadlineTypes) {
        assertNumberOfAndMatchTriggeredDeadlines(
                expectedDeadlineTypes.length,
                exactSequenceOf(createDeadlineTypeMatchers(expectedDeadlineTypes))
        );
    }

    private Matcher<Message>[] createDeadlineTypeMatchers(Class<?>[] expectedDeadlineTypes) {
        List<Matcher<Message>> matchers = new ArrayList<>(expectedDeadlineTypes.length);
        for (Class<?> deadlineType : expectedDeadlineTypes) {
            matchers.add(messageWithPayload(any(deadlineType)));
        }
        //noinspection unchecked
        return matchers.toArray(new Matcher[0]);
    }

    /**
     * Validate whether the number of {@link StubDeadlineManager#getTriggeredDeadlines()} matches the given {@code
     * numberOfExpectedDeadlines}. If it does, invoke the given {@code deadlinesMatcher} with the set of triggered
     * deadlines.
     *
     * @param numberOfExpectedDeadlines the number of expected deadlines to be triggered
     * @param deadlinesMatcher          the matcher used to match the {@link StubDeadlineManager#getTriggeredDeadlines()}
     */
    private void assertNumberOfAndMatchTriggeredDeadlines(int numberOfExpectedDeadlines,
                                                          Matcher<? extends Iterable<?>> deadlinesMatcher) {
        List<ScheduledDeadlineInfo> triggeredDeadlines = deadlineManager.getTriggeredDeadlines();
        if (triggeredDeadlines.size() != numberOfExpectedDeadlines) {
            throw new AxonAssertionError(format(
                    "Got wrong number of triggered deadlines.\nExpected <%s>,\n but got <%s>.",
                    numberOfExpectedDeadlines, triggeredDeadlines.size()
            ));
        }
        assertTriggeredDeadlinesMatching(deadlinesMatcher);
    }

    /**
     * Asserts that no deadlines are scheduled.
     */
    public void assertNoScheduledDeadlines() {
        List<ScheduledDeadlineInfo> scheduledDeadlines = deadlineManager.getScheduledDeadlines();
        if (scheduledDeadlines != null && !scheduledDeadlines.isEmpty()) {
            throw new AxonAssertionError(
                    "Expected no scheduled deadlines, but got " + scheduledDeadlines.size() + " deadlines."
            );
        }
    }

    private void describe(List<ScheduledDeadlineInfo> scheduleDeadlines, Description description) {
        if (scheduleDeadlines.isEmpty()) {
            description.appendText("\n<no scheduled events>");
        }
        for (ScheduledDeadlineInfo item : scheduleDeadlines) {
            description.appendText("\n<")
                       .appendText(item.deadlineMessage().toString())
                       .appendText("> at <")
                       .appendText(formatInstant(item.getScheduleTime()))
                       .appendText(">");
        }
    }

    @SuppressWarnings("unchecked")
    private Matcher<Object>[] createEqualToMatchers(Object[] expected) {
        List<Matcher<?>> matchers = new ArrayList<>(expected.length);
        for (Object deadline : expected) {
            matchers.add(deepEquals(deadline, fieldFilter));
        }
        return matchers.toArray(new Matcher[0]);
    }
}

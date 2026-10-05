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

import org.jspecify.annotations.Nullable;
import org.awaitility.Awaitility;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.test.AxonAssertionError;
import org.axonframework.test.matchers.MapStringEntryMatcher;
import org.axonframework.test.matchers.MatchAllFieldFilter;
import org.axonframework.test.matchers.Matchers;
import org.hamcrest.Matcher;

import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.axonframework.test.matchers.Matchers.deepEquals;

/**
 * Abstract implementation of the {@link AxonTestPhase.Then then-phase} of the {@link AxonTestFixture}.
 *
 * @param <T> The type of {@link Message} validated by this implementation.
 * @author Mateusz Nowak
 * @since 5.0.0
 */
abstract class AxonTestThenMessage<T extends AxonTestPhase.Then.Message<T>>
        implements AxonTestPhase.Then.Message<T> {

    protected final Reporter reporter = new Reporter();

    protected final AxonConfiguration configuration;
    private final AxonTestFixture.Customization customization;
    private final RecordingComponentsRegistry recordings;
    private final Predicate<Message> assertionFilter;

    private final CommandValidator commandValidator;
    protected final @Nullable Throwable actualException;

    /**
     * Constructs an {@code AxonTestThenMessage} for the given parameters.
     *
     * @param configuration   the configuration which this test fixture phase is based on
     * @param customization   collection of customizations made for this test fixture
     * @param recordings      the registry holding recording components for assertions
     * @param assertionFilter filter applied to recorded messages before assertions
     * @param actualException the exception thrown during the when-phase, potentially {@code null}
     */
    public AxonTestThenMessage(
            AxonConfiguration configuration,
            AxonTestFixture.Customization customization,
            RecordingComponentsRegistry recordings,
            Predicate<Message> assertionFilter,
            @Nullable Throwable actualException
    ) {
        this.configuration = configuration;
        this.customization = customization;
        this.recordings = recordings;
        this.assertionFilter = assertionFilter;
        this.actualException = actualException;
        this.commandValidator = new CommandValidator(this::recordedCommands,
                                                     recordings.commandBus()::reset,
                                                     new MatchAllFieldFilter(customization.fieldFilters()));
    }

    private List<EventMessage> recordedEvents() {
        return recordings.eventSink().recorded().stream().filter(assertionFilter).toList();
    }

    private List<CommandMessage> recordedCommands() {
        return recordings.commandBus().recordedCommands().stream().filter(assertionFilter).toList();
    }

    @Override
    public T events(Object... expectedEvents) {
        var publishedEvents = recordedEvents();

        if (expectedEvents.length != publishedEvents.size()) {
            reporter.reportWrongEvent(publishedEvents, Arrays.asList(expectedEvents), actualException);
        }

        Iterator<EventMessage> iterator = publishedEvents.iterator();
        for (Object expectedEvent : expectedEvents) {
            EventMessage actualEvent = iterator.next();
            if (!verifyPayloadEquality(expectedEvent, actualEvent.payload())) {
                reporter.reportWrongEvent(publishedEvents, Arrays.asList(expectedEvents), actualException);
            }
        }
        return self();
    }

    @Override
    public T events(EventMessage... expectedEvents) {
        this.events(Stream.of(expectedEvents).map(Message::payload).toArray());

        var publishedEvents = recordedEvents();
        Iterator<EventMessage> iterator = publishedEvents.iterator();
        for (EventMessage expectedEvent : expectedEvents) {
            EventMessage actualEvent = iterator.next();
            if (!verifyMetadataEquality(expectedEvent.payloadType(),
                                        expectedEvent.metadata(),
                                        actualEvent.metadata())) {
                reporter.reportWrongEvent(publishedEvents, Arrays.asList(expectedEvents), actualException);
            }
        }
        return self();
    }

    @Override
    public T eventsSatisfy(Consumer<List<EventMessage>> consumer) {
        Objects.requireNonNull(consumer, "The consumer may not be null.");
        var publishedEvents = recordedEvents();
        try {
            consumer.accept(publishedEvents);
        } catch (AssertionError e) {
            throw new AxonAssertionError("Events does not satisfy custom assertions", e);
        }
        return self();
    }

    @Override
    public T eventsMatch(Predicate<List<EventMessage>> predicate) {
        Objects.requireNonNull(predicate, "The predicate may not be null.");
        var publishedEvents = recordedEvents();
        var result = predicate.test(publishedEvents);
        if (!result) {
            throw new AxonAssertionError("Events does not satisfy the predicate");
        }
        return self();
    }

    @Override
    public T commands(Object... expectedCommands) {
        commandValidator.assertDispatchedEqualTo(expectedCommands);
        return self();
    }

    @Override
    public T await(Consumer<T> assertion, Duration timeout) {
        Objects.requireNonNull(assertion, "The assertion may not be null.");
        Objects.requireNonNull(timeout, "The timeout may not be null.");
        Awaitility.waitAtMost(timeout)
                  .pollInterval(Duration.ofMillis(50))
                  .untilAsserted(() -> assertion.accept(self()));
        return self();
    }

    @Override
    public T commands(CommandMessage... expectedCommands) {
        commandValidator.assertDispatchedEqualTo(List.of(expectedCommands));
        return self();
    }

    @Override
    public T commandsSatisfy(Consumer<List<CommandMessage>> consumer) {
        Objects.requireNonNull(consumer, "The consumer may not be null.");
        var dispatchedCommands = recordedCommands();
        try {
            consumer.accept(dispatchedCommands);
        } catch (AssertionError e) {
            throw new AxonAssertionError("Commands does not satisfy custom assertions", e);
        }
        return self();
    }

    @Override
    public T commandsMatch(Predicate<List<CommandMessage>> predicate) {
        Objects.requireNonNull(predicate, "The predicate may not be null.");
        var dispatchedCommands = recordedCommands();
        var result = predicate.test(dispatchedCommands);
        if (!result) {
            throw new AxonAssertionError("Events does not satisfy the predicate");
        }
        return self();
    }

    @Override
    public T noCommands() {
        commandValidator.assertDispatchedMatching(Matchers.noCommands());
        return self();
    }

    @Override
    public T exceptionSatisfies(Consumer<Throwable> consumer) {
        Objects.requireNonNull(consumer, "The consumer may not be null.");
        try {
            consumer.accept(actualException);
        } catch (AssertionError e) {
            throw new AxonAssertionError("Exception does not satisfy custom assertions", e);
        }
        return self();
    }

    @Override
    public T exception(Class<? extends Throwable> type, String message) {
        Objects.requireNonNull(type, "The type may not be null.");
        Objects.requireNonNull(message, "The message may not be null.");
        if (actualException == null) {
            throw new AxonAssertionError(
                    "Expected exception of type " + type + " with message '" + message + "' but got none");
        }
        if (!type.isInstance(actualException) || !message.equals(actualException.getMessage())) {
            throw new AxonAssertionError(
                    "Expected " + type + " with message '" + message + "' but got " + actualException);
        }
        return self();
    }

    @Override
    public T exception(Class<? extends Throwable> type) {
        Objects.requireNonNull(type, "The type may not be null.");
        if (actualException == null) {
            throw new AxonAssertionError(
                    "Expected exception of type " + type + " but got none");
        }
        if (!type.isInstance(actualException)) {
            throw new AxonAssertionError(
                    "Expected " + type + " but got " + actualException);
        }
        return self();
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    protected boolean verifyPayloadEquality(@Nullable Object expectedPayload, @Nullable Object actualPayload) {
        if (Objects.equals(expectedPayload, actualPayload)) {
            return true;
        }
        if (expectedPayload != null && actualPayload == null) {
            return false;
        }
        if (expectedPayload == null) {
            return false;
        }
        if (!expectedPayload.getClass().equals(actualPayload.getClass())) {
            return false;
        }
        Matcher<Object> matcher = deepEquals(expectedPayload, new MatchAllFieldFilter(customization.fieldFilters()));
        if (!matcher.matches(actualPayload)) {
            reporter.reportDifferentPayloads(expectedPayload.getClass(), actualPayload, expectedPayload);
        }
        return true;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    protected boolean verifyMetadataEquality(Class<?> eventType,
                                             Map<String, @Nullable String> expectedMetadata,
                                             Map<String, @Nullable String> actualMetadata) {
        MapStringEntryMatcher matcher = new MapStringEntryMatcher(expectedMetadata);
        if (!matcher.matches(actualMetadata)) {
            reporter.reportDifferentMetadata(eventType,
                                             matcher.getMissingEntries(),
                                             matcher.getAdditionalEntries());
        }
        return true;
    }

    @Override
    public AxonTestPhase.Setup and() {
        return new AxonTestFixture(configuration, customization);
    }

    @Override
    public T expect(Consumer<Configuration> function) {
        Objects.requireNonNull(function, "The function may not be null.");
        function.accept(configuration);
        return self();
    }

    @Override
    public T expectAsync(Function<Configuration, CompletableFuture<?>> function) {
        Objects.requireNonNull(function, "The function may not be null.");
        function.apply(configuration).join();
        return self();
    }

    private T self() {
        //noinspection unchecked
        return (T) this;
    }
}

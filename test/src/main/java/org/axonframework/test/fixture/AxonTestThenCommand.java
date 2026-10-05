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
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.test.matchers.PayloadMatcher;
import org.hamcrest.CoreMatchers;
import org.hamcrest.StringDescription;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Implementation of the {@link AxonTestThenMessage then-message-phase} for
 * {@link CommandMessage CommandMessages} of the {@link AxonTestFixture}.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
class AxonTestThenCommand
        extends AxonTestThenMessage<AxonTestPhase.Then.Command>
        implements AxonTestPhase.Then.Command {

    private final Reporter reporter = new Reporter();

    private final Message actualResult;

    /**
     * Constructs an {@code AxonTestThenCommand} for the given parameters.
     *
     * @param configuration        the configuration which this test fixture phase is based on
     * @param customization        collection of customizations made for this test fixture
     * @param recordings           the registry holding recording components for assertions
     * @param assertionFilter      filter applied to recorded messages before assertions
     * @param lastCommandResult    the last result of command handling
     * @param lastCommandException the exception thrown during the when-phase, potentially {@code null}
     */
    public AxonTestThenCommand(
            AxonConfiguration configuration,
            AxonTestFixture.Customization customization,
            RecordingComponentsRegistry recordings,
            Predicate<Message> assertionFilter,
            Message lastCommandResult,
            @Nullable Throwable lastCommandException
    ) {
        super(configuration, customization, recordings, assertionFilter, lastCommandException);
        this.actualResult = lastCommandResult;
    }

    @Override
    public AxonTestPhase.Then.Command success() {
        return resultMessageSatisfies(c -> {
        });
    }

    @Override
    public AxonTestPhase.Then.Command resultMessageSatisfies(Consumer<? super CommandResultMessage> consumer) {
        StringDescription expectedDescription = new StringDescription();
        if (actualException != null) {
            reporter.reportUnexpectedException(actualException, expectedDescription);
        }
        try {
            consumer.accept((CommandResultMessage) actualResult);
        } catch (AssertionError e) {
            reporter.reportWrongResult(actualResult, "Result message to satisfy custom assertions: " + e.getMessage());
        }
        return this;
    }

    @Override
    public AxonTestPhase.Then.Command resultMessagePayload(Object expectedPayload) {
        StringDescription expectedDescription = new StringDescription();
        StringDescription actualDescription = new StringDescription();
        PayloadMatcher<CommandResultMessage> expectedMatcher =
                new PayloadMatcher<>(CoreMatchers.equalTo(expectedPayload));
        expectedMatcher.describeTo(expectedDescription);
        if (actualException != null) {
            reporter.reportUnexpectedException(actualException, expectedDescription);
        } else {
            var messageConverter = configuration.getComponent(MessageConverter.class);
            var convertedPayload = actualResult.payloadAs(expectedPayload.getClass(), messageConverter);
            if (!verifyPayloadEquality(expectedPayload, convertedPayload)) {
                PayloadMatcher<CommandResultMessage> actualMatcher =
                        new PayloadMatcher<>(CoreMatchers.equalTo(convertedPayload));
                actualMatcher.describeTo(actualDescription);
                reporter.reportWrongResult(actualDescription, expectedDescription);
            }
        }
        return this;
    }

    @Deprecated(since = "5.1.0", forRemoval = true)
    @Override
    public AxonTestPhase.Then.Command resultMessagePayloadSatisfies(Consumer<Object> consumer) {
        StringDescription expectedDescription = new StringDescription();
        if (actualException != null) {
            reporter.reportUnexpectedException(actualException, expectedDescription);
        }
        try {
            var payload = actualResult.payload();
            consumer.accept(payload);
        } catch (AssertionError e) {
            reporter.reportWrongResult(actualResult.payload(),
                                       "Result message to satisfy custom assertions: " + e.getMessage());
        }
        return this;
    }

    @Override
    public <T> AxonTestPhase.Then.Command resultMessagePayloadSatisfies(
            Class<T> type,
            Consumer<T> consumer
    ) {
        StringDescription expectedDescription = new StringDescription();
        if (actualException != null) {
            reporter.reportUnexpectedException(actualException, expectedDescription);
        }
        try {
            var messageConverter = configuration.getComponent(MessageConverter.class);
            T convertedPayload = actualResult.payloadAs(type, messageConverter);
            consumer.accept(convertedPayload);
        } catch (AssertionError e) {
            reporter.reportWrongResult(actualResult.payload(),
                                       "Result message to satisfy custom assertions: " + e.getMessage());
        }
        return this;
    }

    @Override
    public AxonTestPhase.Then.Command exception(Class<? extends Throwable> type) {
        StringDescription description = new StringDescription();
        if (actualException == null) {
            reporter.reportUnexpectedReturnValue(actualResult == null ? null : actualResult.payload(), description);
        }
        return super.exception(type);
    }

    @Override
    public AxonTestPhase.Then.Command exception(Class<? extends Throwable> type, String message) {
        StringDescription description = new StringDescription();
        if (actualException == null) {
            reporter.reportUnexpectedReturnValue(actualResult == null ? null : actualResult.payload(), description);
        }
        return super.exception(type, message);
    }

    @Override
    public AxonTestPhase.Then.Command exceptionSatisfies(Consumer<Throwable> consumer) {
        StringDescription description = new StringDescription();
        if (actualException == null) {
            reporter.reportUnexpectedReturnValue(actualResult == null ? null : actualResult.payload(), description);
        }
        return super.exceptionSatisfies(consumer);
    }
}

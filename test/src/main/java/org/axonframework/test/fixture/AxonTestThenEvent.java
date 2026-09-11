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
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.hamcrest.StringDescription;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Implementation of the {@link AxonTestThenMessage then-message-phase} for
 * {@link EventMessage EventMessages} of the {@link AxonTestFixture}.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
class AxonTestThenEvent
        extends AxonTestThenMessage<AxonTestPhase.Then.Event>
        implements AxonTestPhase.Then.Event {

    /**
     * Constructs an {@code AxonTestThenEvent} for the given parameters.
     *
     * @param configuration   the configuration which this test fixture phase is based on
     * @param customization   collection of customizations made for this test fixture
     * @param recordings      the registry holding recording components for assertions
     * @param assertionFilter filter applied to recorded messages before assertions
     * @param actualException the exception thrown during the when-phase, potentially {@code null}
     */
    public AxonTestThenEvent(
            AxonConfiguration configuration,
            AxonTestFixture.Customization customization,
            RecordingComponentsRegistry recordings,
            Predicate<Message> assertionFilter,
            @Nullable Throwable actualException
    ) {
        super(configuration, customization, recordings, assertionFilter, actualException);
    }

    @Override
    public AxonTestPhase.Then.Event success() {
        StringDescription expectedDescription = new StringDescription();
        if (actualException != null) {
            reporter.reportUnexpectedException(actualException, expectedDescription);
        }
        return this;
    }

    @Override
    public AxonTestPhase.Then.Event exception(Class<? extends Throwable> type) {
        StringDescription description = new StringDescription();
        if (actualException == null) {
            reporter.reportUnexpectedReturnValue(MessageStream.Empty.class.getSimpleName(), description);
        }
        return super.exception(type);
    }

    @Override
    public AxonTestPhase.Then.Event exception(Class<? extends Throwable> type, String message) {
        StringDescription description = new StringDescription();
        if (actualException == null) {
            reporter.reportUnexpectedReturnValue(MessageStream.Empty.class.getSimpleName(), description);
        }
        return super.exception(type, message);
    }

    @Override
    public AxonTestPhase.Then.Event exceptionSatisfies(Consumer<Throwable> consumer) {
        StringDescription description = new StringDescription();
        if (actualException == null) {
            reporter.reportUnexpectedReturnValue(MessageStream.Empty.class.getSimpleName(), description);
        }
        return super.exceptionSatisfies(consumer);
    }
}

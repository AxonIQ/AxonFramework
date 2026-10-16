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

package org.axonframework.modelling;

import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating the {@link FallbackEntityIdResolver}.
 *
 * @author Steven van Beelen
 */
class FallbackEntityIdResolverTest {

    private static final Message MESSAGE = new GenericCommandMessage(new MessageType(Payload.class), new Payload());
    private static final ProcessingContext CONTEXT = StubProcessingContext.forMessage(MESSAGE);

    @Test
    void resolvesWithThePrimaryWhenItSucceeds() throws EntityIdResolutionException {
        // given
        EntityIdResolver<String> primary = (message, context) -> "primary-id";
        EntityIdResolver<String> secondary = (message, context) -> "secondary-id";
        FallbackEntityIdResolver<String> testSubject = new FallbackEntityIdResolver<>(primary, secondary);

        // when
        String result = testSubject.resolve(MESSAGE, CONTEXT);

        // then
        assertThat(result).isEqualTo("primary-id");
    }

    @Test
    void resolvesWithTheSecondaryWhenThePrimaryFails() throws EntityIdResolutionException {
        // given
        EntityIdResolver<String> primary = failingResolver();
        EntityIdResolver<String> secondary = (message, context) -> "secondary-id";
        FallbackEntityIdResolver<String> testSubject = new FallbackEntityIdResolver<>(primary, secondary);

        // when
        String result = testSubject.resolve(MESSAGE, CONTEXT);

        // then
        assertThat(result).isEqualTo("secondary-id");
    }

    @Test
    void propagatesFailureWhenBothFail() {
        // given
        EntityIdResolver<String> primary = failingResolver();
        EntityIdResolver<String> secondary = failingResolver();
        FallbackEntityIdResolver<String> testSubject = new FallbackEntityIdResolver<>(primary, secondary);

        // when / then
        assertThatThrownBy(() -> testSubject.resolve(MESSAGE, CONTEXT))
                .isInstanceOf(EntityIdResolutionException.class);
    }

    private static EntityIdResolver<String> failingResolver() {
        return (message, context) -> {
            throw new EntityIdResolutionException(message.payloadType(), List.of());
        };
    }

    private record Payload() {

    }
}

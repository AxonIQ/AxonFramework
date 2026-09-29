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

package org.axonframework.deadline;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.axonframework.common.ObjectUtils;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageTestSuite;
import org.axonframework.messaging.core.MessageType;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link GenericDeadlineMessage}.
 *
 * @author Steven van Beelen
 */
class GenericDeadlineMessageTest extends MessageTestSuite<DeadlineMessage> {

    private static final String TEST_DEADLINE_NAME = "deadlineName";
    private static final Instant TEST_TIMESTAMP = Instant.now();

    @Override
    protected DeadlineMessage buildDefaultMessage() {
        Message delegate =
                new GenericMessage(TEST_IDENTIFIER, TEST_TYPE, TEST_PAYLOAD, TEST_PAYLOAD_TYPE, TEST_METADATA);
        return new GenericDeadlineMessage(TEST_DEADLINE_NAME, delegate, () -> TEST_TIMESTAMP);
    }

    @Override
    protected <P> DeadlineMessage buildMessage(@Nullable P payload) {
        return new GenericDeadlineMessage(TEST_DEADLINE_NAME,
                                          new MessageType(ObjectUtils.nullSafeTypeOf(payload)),
                                          payload);
    }

    @Override
    protected void validateDefaultMessage(@NonNull DeadlineMessage result) {
        assertThat(TEST_DEADLINE_NAME).isEqualTo(result.getDeadlineName());
        assertThat(TEST_TIMESTAMP).isEqualTo(result.timestamp());
    }

    @Override
    protected void validateMessageSpecifics(@NonNull DeadlineMessage actual, @NonNull DeadlineMessage result) {
        assertThat(actual.getDeadlineName()).isEqualTo(result.getDeadlineName());
        assertThat(actual.timestamp()).isEqualTo(result.timestamp());
    }
}
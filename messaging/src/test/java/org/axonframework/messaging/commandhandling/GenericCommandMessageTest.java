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

package org.axonframework.messaging.commandhandling;

import org.assertj.core.api.Assertions;
import org.axonframework.common.ObjectUtils;
import org.axonframework.common.TypeReference;
import org.axonframework.conversion.ChainingContentTypeConverter;
import org.axonframework.conversion.ConversionException;
import org.axonframework.conversion.Converter;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageTestSuite;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link GenericCommandMessage}.
 *
 * @author Allard Buijze
 */
class GenericCommandMessageTest extends MessageTestSuite<GenericCommandMessage> {

    private static final @Nullable String TEST_ROUTING_KEY = "myRoutingKey";
    private static final @Nullable Integer TEST_PRIORITY = 42;

    @Override
    protected GenericCommandMessage buildDefaultMessage() {
        Message delegate =
                new GenericMessage(TEST_IDENTIFIER, TEST_TYPE, TEST_PAYLOAD, TEST_PAYLOAD_TYPE, TEST_METADATA);
        return new GenericCommandMessage(delegate, TEST_ROUTING_KEY, TEST_PRIORITY);
    }

    @Override
    protected <P> GenericCommandMessage buildMessage(@Nullable P payload) {
        return new GenericCommandMessage(new MessageType(ObjectUtils.nullSafeTypeOf(payload)), payload);
    }

    @Test
    void toStringIsAsExpected() {
        String actual = new GenericCommandMessage(
                TEST_TYPE, "MyPayload", Metadata.with("key", "value").and("key2", "13"), "routingKey", 42
        ).toString();

        assertTrue(actual.startsWith("GenericCommandMessage{type={message#0.0.1}, payload={MyPayload}, metadata={"),
                   "Wrong output: " + actual);
        assertTrue(actual.contains("'key'->'value'"), "Wrong output: " + actual);
        assertTrue(actual.contains("'key2'->'13'"), "Wrong output: " + actual);
        assertTrue(actual.contains(", routingKey='routingKey'"), "Wrong output: " + actual);
        assertTrue(actual.contains(", priority='42'"), "Wrong output: " + actual);
        assertEquals(203, actual.length(), "Wrong output: " + actual);
    }

    @Nested
    class WithConverter {

        private Converter converter;
        private String exStringPayload = "payload";
        private byte[] exBytePayload = exStringPayload.getBytes(StandardCharsets.UTF_8);

        @BeforeEach
        void setUp() {
            converter = spy(new ChainingContentTypeConverter());
        }

        @AfterEach
        void tearDown() {
            verifyNoMoreInteractions(converter);
        }

        @Test
        void payloadAsClassReturnsPayloadWithoutConversionOnSameType() {
            GenericCommandMessage exMessage = buildMessage(exStringPayload)
                    .withConverter(converter);

            // test
            String acPayload = exMessage.payloadAs(String.class);

            assertThat(acPayload).isEqualTo(exStringPayload);
        }

        @Test
        void payloadAsClassInvokesConverterOnDifferentType() {
            GenericCommandMessage exMessage = buildMessage(exBytePayload)
                    .withConverter(converter);

            // test
            String acPayload = exMessage.payloadAs(String.class);

            assertThat(acPayload).isEqualTo(exStringPayload);
            verify(converter).convert(exBytePayload, (Type) String.class);
        }

        @Test
        void payloadAsClassFailsWithConversionExceptionWithoutConverter() {
            GenericCommandMessage exMessage = buildMessage(exBytePayload);

            // test
            Assertions.assertThatThrownBy(() -> exMessage.payloadAs(Integer.class))
                      .isInstanceOf(ConversionException.class);
        }

        @Test
        void payloadAsTypeRefInvokesConverter() {
            GenericCommandMessage exMessage = buildMessage(exBytePayload)
                    .withConverter(converter);

            // test
            String acPayload = exMessage.payloadAs(new TypeReference<String>() {
            });

            assertThat(acPayload).isEqualTo(exStringPayload);
            verify(converter).convert(exBytePayload, (Type) String.class);
        }

        @Test
        void payloadAsCachesConversion() {
            GenericCommandMessage exMessage = buildMessage(exBytePayload)
                    .withConverter(converter);

            // test
            String acPayload = exMessage.payloadAs(String.class);
            String acPayload2 = exMessage.payloadAs(String.class);

            assertThat(acPayload).isEqualTo(exStringPayload);
            assertThat(acPayload2).isEqualTo(exStringPayload);
            verify(converter, times(1)).convert(exBytePayload, (Type) String.class);
        }

        @Test
        void withConverterReturnsNewInstanceOfSameConcreteType() {
            // given
            GenericCommandMessage original = buildMessage(exBytePayload);

            // when
            GenericCommandMessage result = original.withConverter(converter);

            // then
            assertThat(result)
                    .isInstanceOf(GenericCommandMessage.class)
                    .isNotSameAs(original);
        }

        @Test
        void withConverterPreservesMembers() {
            // given
            GenericCommandMessage original = buildMessage(exBytePayload);

            // when
            GenericCommandMessage result = original.withConverter(converter);

            // then
            assertThat(result)
                    .isInstanceOf(GenericCommandMessage.class)
                    .isNotSameAs(original);
            assertThat(result.identifier()).isEqualTo(original.identifier());
            assertThat(result.type()).isEqualTo(original.type());
            assertThat(result.payload()).isEqualTo(original.payload());
            assertThat(result.payloadType()).isEqualTo(original.payloadType());
            assertThat(result.metadata()).isEqualTo(original.metadata());
            assertThat(result.priority()).isEqualTo(original.priority());
            assertThat(result.routingKey()).isEqualTo(original.routingKey());
        }
    }

    @Nested
    class MetadataModification {

        @Test
        void andMetadataKeepsRoutingKeyAndPriority() {
            // given
            GenericCommandMessage original = new GenericCommandMessage(
                    new MessageType("command"), "payload", Metadata.emptyInstance(), "id-1", 5
            );

            // when
            CommandMessage result = original.andMetadata(Metadata.with("key", "value"));

            // then
            assertThat(result.routingKey()).contains("id-1");
            assertThat(result.priority()).hasValue(5);
            assertThat(result.metadata()).containsEntry("key", "value");
        }

        @Test
        void withMetadataKeepsRoutingKeyAndPriority() {
            // given
            GenericCommandMessage original = new GenericCommandMessage(
                    new MessageType("command"), "payload", Metadata.emptyInstance(), "id-1", 5
            );

            // when
            CommandMessage result = original.withMetadata(Metadata.with("key", "value"));

            // then
            assertThat(result.routingKey()).contains("id-1");
            assertThat(result.priority()).hasValue(5);
            assertThat(result.metadata()).containsEntry("key", "value");
        }
    }
}

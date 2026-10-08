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

import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.conversion.ConversionException;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

import java.lang.reflect.Type;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link MetadataEntityIdResolver}.
 *
 * @author Steven van Beelen
 */
class MetadataEntityIdResolverTest {

    private static final String METADATA_KEY = "targetEntityId";

    private final MetadataEntityIdResolver<String> testSubject = MetadataEntityIdResolver.forKey(METADATA_KEY);

    @Nested
    class Construction {

        @Test
        void rejectsAnEmptyMetadataKey() {
            assertThatThrownBy(() -> MetadataEntityIdResolver.forKey(""))
                    .isInstanceOf(org.axonframework.common.AxonConfigurationException.class);
        }

        @SuppressWarnings("DataFlowIssue")
        @Test
        void rejectsANullMetadataKey() {
            assertThatThrownBy(() -> MetadataEntityIdResolver.forKey(null))
                    .isInstanceOf(org.axonframework.common.AxonConfigurationException.class);
        }

        @SuppressWarnings("DataFlowIssue")
        @Test
        void rejectsANullIdTypeWithAConverter() {
            assertThatThrownBy(() -> MetadataEntityIdResolver.forKey(METADATA_KEY, null, PassThroughConverter.INSTANCE))
                    .isInstanceOf(NullPointerException.class);
        }

        @SuppressWarnings("DataFlowIssue")
        @Test
        void rejectsANullConverter() {
            assertThatThrownBy(() -> MetadataEntityIdResolver.forKey(METADATA_KEY, String.class, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class Resolve {

        @Test
        void resolvesTheValueOfThePresentMetadataKey() throws EntityIdResolutionException {
            // given
            record Payload() {

            }
            Message message = new GenericCommandMessage(
                    new MessageType(Payload.class), new Payload(), Metadata.with(METADATA_KEY, "entity-1")
            );

            // when
            String result = testSubject.resolve(message, StubProcessingContext.forMessage(message));

            // then
            assertThat(result).isEqualTo("entity-1");
        }

        @Test
        void throwsWhenTheMetadataKeyIsAbsent() {
            // given
            record Payload() {

            }
            Message message = new GenericCommandMessage(new MessageType(Payload.class), new Payload());

            // when / then
            assertThatThrownBy(() -> testSubject.resolve(message, StubProcessingContext.forMessage(message)))
                    .isInstanceOf(EntityIdResolutionException.class);
        }

        @Test
        void throwsWhenTheMetadataValueIsBlank() {
            // given
            record Payload() {

            }
            Message message = new GenericCommandMessage(
                    new MessageType(Payload.class), new Payload(), Metadata.with(METADATA_KEY, "   ")
            );

            // when / then
            assertThatThrownBy(() -> testSubject.resolve(message, StubProcessingContext.forMessage(message)))
                    .isInstanceOf(EntityIdResolutionException.class);
        }
    }

    @Nested
    class ResolveWithConverter {

        @Test
        void delegatesTheResolvedMetadataValueAndIdTypeToTheGivenConverter() throws EntityIdResolutionException {
            // given
            Converter converter = spy(PassThroughConverter.INSTANCE);
            MetadataEntityIdResolver<String> testSubject =
                    MetadataEntityIdResolver.forKey(METADATA_KEY, String.class, converter);
            record Payload() {

            }
            Message message = new GenericCommandMessage(
                    new MessageType(Payload.class), new Payload(), Metadata.with(METADATA_KEY, "entity-1")
            );

            // when
            String result = testSubject.resolve(message, StubProcessingContext.forMessage(message));

            // then
            assertThat(result).isEqualTo("entity-1");
            verify(converter).convert("entity-1", String.class);
        }

        @Test
        void convertsTheResolvedMetadataValueIntoANonStringIdTypeUsingARealConverter()
                throws EntityIdResolutionException {
            // given
            Converter converter = new JacksonConverter();
            MetadataEntityIdResolver<UUID> testSubject =
                    MetadataEntityIdResolver.forKey(METADATA_KEY, UUID.class, converter);
            UUID id = UUID.randomUUID();
            record Payload() {

            }
            Message message = new GenericCommandMessage(
                    new MessageType(Payload.class), new Payload(), Metadata.with(METADATA_KEY, id.toString())
            );

            // when
            UUID result = testSubject.resolve(message, StubProcessingContext.forMessage(message));

            // then
            assertThat(result).isEqualTo(id);
        }

        @Test
        void propagatesANullPointerExceptionWhenTheConverterReturnsNull() {
            // given
            Converter converter = new Converter() {
                @Override
                public <T> T convert(Object input, @NonNull Type targetType) {
                    return null;
                }

                @Override
                public void describeTo(@NonNull ComponentDescriptor descriptor) {
                }
            };
            MetadataEntityIdResolver<String> testSubject =
                    MetadataEntityIdResolver.forKey(METADATA_KEY, String.class, converter);
            record Payload() {

            }
            Message message = new GenericCommandMessage(
                    new MessageType(Payload.class), new Payload(), Metadata.with(METADATA_KEY, "entity-1")
            );

            // when / then
            assertThatThrownBy(() -> testSubject.resolve(message, StubProcessingContext.forMessage(message)))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void propagatesAConversionExceptionThrownByTheConverter() {
            // given
            Converter converter = new Converter() {
                @Override
                public <T> T convert(Object input, @NonNull Type targetType) {
                    throw new ConversionException("Cannot convert the resolved metadata value");
                }

                @Override
                public void describeTo(@NonNull ComponentDescriptor descriptor) {
                }
            };
            MetadataEntityIdResolver<String> testSubject =
                    MetadataEntityIdResolver.forKey(METADATA_KEY, String.class, converter);
            record Payload() {

            }
            Message message = new GenericCommandMessage(
                    new MessageType(Payload.class), new Payload(), Metadata.with(METADATA_KEY, "entity-1")
            );

            // when / then
            assertThatThrownBy(() -> testSubject.resolve(message, StubProcessingContext.forMessage(message)))
                    .isInstanceOf(ConversionException.class);
        }
    }
}

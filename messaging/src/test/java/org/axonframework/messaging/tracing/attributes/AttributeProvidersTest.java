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

package org.axonframework.messaging.tracing.attributes;

import org.axonframework.messaging.core.LegacyResources;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.interception.CorrelationDataInterceptor;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeProvidersTest {

    private final EventMessage event = EventTestUtils.asEventMessage("the-payload");

    @Nested
    class MessageId {

        @Test
        void addsTheMessageIdentifier() {
            // when
            var attributes = new MessageIdSpanAttributesProvider().provideForMessage(event, null);

            // then
            assertThat(attributes).containsEntry(MessageIdSpanAttributesProvider.DEFAULT_ATTRIBUTE_KEY, event.identifier());
        }
    }

    @Nested
    class MessageType {

        @Test
        void addsTheMessageType() {
            // when
            var attributes = new MessageTypeSpanAttributesProvider().provideForMessage(event, null);

            // then
            assertThat(attributes)
                    .containsEntry(MessageTypeSpanAttributesProvider.DEFAULT_ATTRIBUTE_KEY, event.type().toString());
        }
    }

    @Nested
    class MetadataAttributes {

        @Test
        void addsAllMetadataByDefault() {
            // given
            Message withMetadata = event.andMetadata(java.util.Map.of("tenant", "acme"));

            // when
            var attributes = new MetadataSpanAttributesProvider().provideForMessage(withMetadata, null);

            // then
            assertThat(attributes)
                    .containsEntry(MetadataSpanAttributesProvider.METADATA_PREFIX + "tenant", "acme");
        }

        @Test
        void addsOnlyAllowlistedKeysWhenConfigured() {
            // given
            Message withMetadata = event.andMetadata(java.util.Map.of("tenant", "acme", "secret", "hidden"));

            // when
            var attributes = new MetadataSpanAttributesProvider(Set.of("tenant")).provideForMessage(withMetadata, null);

            // then
            assertThat(attributes)
                    .containsEntry(MetadataSpanAttributesProvider.METADATA_PREFIX + "tenant", "acme")
                    .doesNotContainKey(MetadataSpanAttributesProvider.METADATA_PREFIX + "secret");
        }

        @Test
        void usesTheGivenPrefixWhenOverridden() {
            // given the Axon Framework 4 prefix
            Message withMetadata = event.andMetadata(java.util.Map.of("tenant", "acme"));

            // when
            var attributes =
                    new MetadataSpanAttributesProvider("axon_metadata_", Set.of()).provideForMessage(withMetadata, null);

            // then
            assertThat(attributes).containsEntry("axon_metadata_tenant", "acme");
        }

        @Test
        void addsCorrelationDataStagedOnTheProcessingContext() {
            // given correlation data staged by the CorrelationDataInterceptor, not yet on the message
            var context = new StubProcessingContext()
                    .withResource(CorrelationDataInterceptor.CORRELATION_DATA,
                                  java.util.Map.of("correlationId", "corr-1"));

            // when
            var attributes = new MetadataSpanAttributesProvider().provideForMessage(event, context);

            // then
            assertThat(attributes)
                    .containsEntry(MetadataSpanAttributesProvider.METADATA_PREFIX + "correlationId", "corr-1");
        }

        @Test
        void stagedCorrelationDataOverridesMessageMetadataWithTheSameKey() {
            // given the same key on the message and staged on the context -- mirrors the interceptor's andMetadata merge
            Message withMetadata = event.andMetadata(java.util.Map.of("correlationId", "stale"));
            var context = new StubProcessingContext()
                    .withResource(CorrelationDataInterceptor.CORRELATION_DATA,
                                  java.util.Map.of("correlationId", "corr-1"));

            // when
            var attributes = new MetadataSpanAttributesProvider().provideForMessage(withMetadata, context);

            // then
            assertThat(attributes)
                    .containsEntry(MetadataSpanAttributesProvider.METADATA_PREFIX + "correlationId", "corr-1");
        }

        @Test
        void appliesTheAllowlistToStagedCorrelationData() {
            // given
            var context = new StubProcessingContext()
                    .withResource(CorrelationDataInterceptor.CORRELATION_DATA,
                                  java.util.Map.of("correlationId", "corr-1", "secret", "hidden"));

            // when
            var attributes =
                    new MetadataSpanAttributesProvider(Set.of("correlationId")).provideForMessage(event, context);

            // then
            assertThat(attributes)
                    .containsEntry(MetadataSpanAttributesProvider.METADATA_PREFIX + "correlationId", "corr-1")
                    .doesNotContainKey(MetadataSpanAttributesProvider.METADATA_PREFIX + "secret");
        }
    }

    @Nested
    class CustomAttributeKeyOverride {

        @Test
        void singleValueProvidersUseTheGivenKey() {
            // given the legacy Axon Framework 4 keys
            var id = new MessageIdSpanAttributesProvider("axon_message_id").provideForMessage(event, null);
            var type = new MessageTypeSpanAttributesProvider("axon_message_type").provideForMessage(event, null);

            // then
            assertThat(id).containsKey("axon_message_id");
            assertThat(type).containsEntry("axon_message_type", event.type().toString());
        }
    }

    @Nested
    class AggregateIdentifier {

        @Test
        void addsAggregateIdentifierFromLegacyResource() {
            // given
            StubProcessingContext context = new StubProcessingContext();
            context.putResource(LegacyResources.AGGREGATE_IDENTIFIER_KEY, "aggregate-42");

            // when
            var attributes = new AggregateIdentifierSpanAttributesProvider().provideForMessage(event, context);

            // then
            assertThat(attributes)
                    .containsEntry(AggregateIdentifierSpanAttributesProvider.DEFAULT_ATTRIBUTE_KEY, "aggregate-42");
        }

        @Test
        void emptyWhenNoContext() {
            // when
            var attributes = new AggregateIdentifierSpanAttributesProvider().provideForMessage(event, null);

            // then
            assertThat(attributes).isEmpty();
        }

        @Test
        void emptyWhenResourceAbsent() {
            // when
            var attributes =
                    new AggregateIdentifierSpanAttributesProvider().provideForMessage(event, new StubProcessingContext());

            // then
            assertThat(attributes).isEmpty();
        }
    }
}

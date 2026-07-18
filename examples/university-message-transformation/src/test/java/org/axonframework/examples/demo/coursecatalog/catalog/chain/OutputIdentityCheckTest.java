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

package org.axonframework.examples.demo.coursecatalog.catalog.chain;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import io.axoniq.framework.messaging.transformation.ChainConfigurationException;
import io.axoniq.framework.messaging.transformation.events.EventTransformation;
import io.axoniq.framework.messaging.transformation.events.EventTransformerChain;
import org.axonframework.examples.demo.coursecatalog.catalog.testutil.ChainTester;
import org.axonframework.messaging.core.MessageType;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

class OutputIdentityCheckTest {

    private static final MessageType DECLARED_TO = new MessageType("coursecatalog.SamplePayload", "2.0.0");
    private static final MessageType RESOLVED_MISMATCH = new MessageType("coursecatalog.WrongPayload", "1.0.0");

    @Test
    void misconfiguredMapperOutputIdentityRaisesChainConfigurationException() {
        // A 1:1 transformation whose mapper returns a typed POJO that the configured resolver
        // resolves to a MessageType other than the declared `to`. The chain MUST refuse it.
        EventTransformation misconfiguredTransformation = EventTransformation
                .from(new MessageType("coursecatalog.SamplePayload", "1.0.0"))
                .to(DECLARED_TO)
                .transform(JsonNode.class, (in, ctx) -> new WrongPayload());

        EventTransformerChain chain = EventTransformerChain.builder().register(misconfiguredTransformation).build();

        ChainTester.forChain(chain)
                   .usingTypeResolver(cls -> cls == WrongPayload.class
                           ? Optional.of(RESOLVED_MISMATCH)
                           : Optional.empty())
                   .usingConverter(new NeverInvokedConverter())
                   .given()
                   .messageType("coursecatalog.SamplePayload", "1.0.0")
                   .payload(JsonNodeFactory.instance.objectNode())
                   .when()
                   .then()
                   .exceptionSatisfies(ex -> assertThat(ex)
                           .isInstanceOf(CompletionException.class)
                           .cause()
                           .isInstanceOf(ChainConfigurationException.class)
                           .hasMessageContaining(DECLARED_TO.toString())
                           .hasMessageContaining(WrongPayload.class.getName()));
    }

    /** Output payload whose runtime class the resolver maps to a non-matching MessageType. */
    private static final class WrongPayload {
    }

    /**
     * Converter that fails loudly if the chain ever asks it to convert; the input here is
     * already a {@link JsonNode}, so the chain's fast path must skip conversion.
     */
    private static final class NeverInvokedConverter
            implements org.axonframework.messaging.core.conversion.MessageConverter {
        @Override
        public <M extends org.axonframework.messaging.core.Message, T> T convertPayload(
                M message, java.lang.reflect.Type targetType) {
            throw new AssertionError("converter should not be invoked when input is already typed");
        }

        @Override
        public <M extends org.axonframework.messaging.core.Message> M convertMessage(
                M message, java.lang.reflect.Type targetType) {
            throw new AssertionError("convertMessage unexpectedly invoked");
        }

        @Override
        public <T> T convert(@Nullable Object input, java.lang.reflect.Type targetType) {
            throw new AssertionError("convert unexpectedly invoked");
        }

        @Override
        public void describeTo(org.axonframework.common.infra.ComponentDescriptor descriptor) {
            descriptor.describeProperty("kind", "never-invoked");
        }
    }
}

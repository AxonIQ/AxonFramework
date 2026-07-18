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

import io.axoniq.framework.messaging.transformation.events.EventTransformation;
import io.axoniq.framework.messaging.transformation.events.EventTransformerChain;
import org.axonframework.examples.demo.coursecatalog.catalog.testutil.ChainTester;
import org.axonframework.messaging.core.MessageType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

class MapperExceptionPropagationTest {

    private static final MessageType INPUT_TYPE = new MessageType("custom.event", "1.0.0");
    private static final MessageType OUTPUT_TYPE = new MessageType("custom.event", "2.0.0");

    @Test
    void runtimeExceptionFromMapperPropagatesThroughChain() {
        EventTransformation throwing = EventTransformation
                .from(INPUT_TYPE)
                .to(OUTPUT_TYPE)
                .transform(JsonNode.class, (in, ctx) -> {
                    throw new IllegalStateException("boom from mapper");
                });

        EventTransformerChain chain = EventTransformerChain.builder().register(throwing).build();

        ChainTester.forChain(chain)
                   .given()
                   .messageType(INPUT_TYPE.qualifiedName().name(), INPUT_TYPE.version())
                   .payload(JsonNodeFactory.instance.objectNode())
                   .when()
                   .then()
                   .exceptionSatisfies(ex -> assertThat(ex)
                           .isInstanceOf(CompletionException.class)
                           .cause()
                           .isInstanceOf(IllegalStateException.class)
                           .hasMessage("boom from mapper"));
    }
}

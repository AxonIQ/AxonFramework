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

import io.axoniq.framework.messaging.transformation.ChainConfigurationException;
import io.axoniq.framework.messaging.transformation.events.EventTransformation;
import io.axoniq.framework.messaging.transformation.events.EventTransformerChain;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.catalog.testutil.ChainTester;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Demonstrates how the chain resolves a stored event when more than one transformation could apply: exact identity
 * matches win over predicates regardless of registration order, a predicate runs only as a fallback, and two
 * transformations claiming the same exact source are rejected when the chain is built.
 */
class SemanticMatchingTest {

    private static final String WELCOME = CourseCatalogMessageNames.WELCOME_MESSAGE_SENT;
    private static final MessageType VIA_EXACT = welcome("9.0.0");
    private static final MessageType VIA_PREDICATE = welcome("8.0.0");

    @Test
    void exactMatchWinsOverAnOverlappingPredicateWhenThePredicateIsRegisteredFirst() {
        EventTransformerChain chain = EventTransformerChain.builder()
                                                           .register(everyBetaViaPredicate())
                                                           .register(beta05ViaExact())
                                                           .build();

        // a 0.5 event is matched by both, but the exact match wins
        runWelcome(chain, "0.5").outputType(VIA_EXACT);
    }

    @Test
    void exactMatchWinsOverAnOverlappingPredicateWhenTheExactIsRegisteredFirst() {
        EventTransformerChain chain = EventTransformerChain.builder()
                                                           .register(beta05ViaExact())
                                                           .register(everyBetaViaPredicate())
                                                           .build();

        // registration order is irrelevant: the exact match still wins
        runWelcome(chain, "0.5").outputType(VIA_EXACT);
    }

    @Test
    void thePredicateAppliesOnlyToVersionsNoExactMatchClaims() {
        EventTransformerChain chain = EventTransformerChain.builder()
                                                           .register(beta05ViaExact())
                                                           .register(everyBetaViaPredicate())
                                                           .build();

        // 0.7 has no exact match, so it falls back to the predicate
        runWelcome(chain, "0.7").outputType(VIA_PREDICATE);
    }

    @Test
    void twoTransformationsOnTheSameExactSourceAreRejectedWhenTheChainIsBuilt() {
        EventTransformerChain.Builder builder =
                EventTransformerChain.builder()
                                     .register(beta05ViaExact())
                                     .register(EventTransformation.from(welcome("0.5"))
                                                                  .to(welcome("7.0.0"))
                                                                  .transform(JsonNode.class, (in, ctx) -> in));

        assertThatThrownBy(builder::build)
                .isInstanceOf(ChainConfigurationException.class)
                .hasMessageContaining(welcome("0.5").toString());
    }

    private static EventTransformation everyBetaViaPredicate() {
        return EventTransformation.from(type -> type.version().startsWith("0."))
                                  .declaringFromTypes(new QualifiedName(WELCOME))
                                  .to(VIA_PREDICATE)
                                  .transform(JsonNode.class, (in, ctx) -> in);
    }

    private static EventTransformation beta05ViaExact() {
        return EventTransformation.from(welcome("0.5"))
                                  .to(VIA_EXACT)
                                  .transform(JsonNode.class, (in, ctx) -> in);
    }

    private static MessageType welcome(String version) {
        return new MessageType(WELCOME, version);
    }

    private static ChainTester.Then runWelcome(EventTransformerChain chain, String version) {
        JsonNode payload = JsonNodeFactory.instance.objectNode().put("studentId", "Student:alice").put("body", "Hi");
        return ChainTester.forChain(chain)
                          .given()
                          .messageType(WELCOME, version)
                          .payload(payload)
                          .when()
                          .then()
                          .success();
    }
}

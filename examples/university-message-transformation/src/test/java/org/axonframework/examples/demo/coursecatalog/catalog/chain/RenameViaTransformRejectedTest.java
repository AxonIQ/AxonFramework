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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.*;

/**
 * A payload-mapping {@code transform(...)} may only change an event's version, not its qualified
 * name. To change the name itself, use {@link EventTransformation#rename(MessageType, MessageType)}
 * instead (see {@link RenameTest}). This test pins the guard: a {@code transform(...)} whose {@code to}
 * changes the name is rejected, while a same-name version bump is allowed. A concrete {@code from} is
 * rejected at registration; a predicate {@code from} only at read time, as its matched name is known
 * per-event.
 */
class RenameViaTransformRejectedTest {

    @Nested
    class ConcreteFrom {

        @Test
        void changingTheNameViaTransformIsRejectedAtRegistration() {
            // given: a transform that would change CoursePublished into StudentRegistered
            EventTransformation renameTransformation =
                    EventTransformation.from(new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "1.0.0"))
                                       .to(new MessageType(CourseCatalogMessageNames.STUDENT_REGISTERED, "1.0.0"))
                                       .transform(JsonNode.class, (node, ctx) -> node);
            EventTransformerChain.Builder builder = EventTransformerChain.builder();

            // when / then
            assertThatThrownBy(() -> builder.register(renameTransformation))
                    .isInstanceOf(ChainConfigurationException.class)
                    .hasMessageContaining("rename")
                    .hasMessageContaining(CourseCatalogMessageNames.COURSE_PUBLISHED)
                    .hasMessageContaining(CourseCatalogMessageNames.STUDENT_REGISTERED);
        }

        @Test
        void bumpingTheVersionWhileKeepingTheNameIsAllowed() {
            // given: a same-name version bump (the supported structural upcast)
            EventTransformation structuralUpcast =
                    EventTransformation.from(new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "1.0.0"))
                                       .to(new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "2.0.0"))
                                       .transform(JsonNode.class, (node, ctx) -> node);

            // when / then: registration and build succeed without throwing
            assertThatNoException().isThrownBy(() -> EventTransformerChain.builder()
                                                                          .register(structuralUpcast)
                                                                          .build());
        }
    }

    @Nested
    class PredicateFrom {

        @Test
        void changingTheNameViaTransformPredicateIsRejectedAtReadTime() {
            // given: registration is allowed because a predicate's matched source name is not
            // known statically; the name change only surfaces once a CoursePublished event matches.
            MessageType coursePublishedV1 = new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "1.0.0");
            EventTransformation renameTransformation =
                    EventTransformation.from(type -> type.equals(coursePublishedV1))
                                       .to(new MessageType(CourseCatalogMessageNames.STUDENT_REGISTERED, "1.0.0"))
                                       .transform(JsonNode.class, (node, ctx) -> node);
            EventTransformerChain chain = EventTransformerChain.builder()
                                                               .register(renameTransformation)
                                                               .build();

            // when / then: the name change is rejected as the matching event flows through the chain
            ChainTester.forChain(chain)
                       .given()
                       .messageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "1.0.0")
                       .payload(JsonNodeFactory.instance.objectNode())
                       .when()
                       .then()
                       .exceptionSatisfies(ex -> assertThat(ex)
                               .isInstanceOf(CompletionException.class)
                               .cause()
                               .isInstanceOf(ChainConfigurationException.class)
                               .hasMessageContaining("rename")
                               .hasMessageContaining(CourseCatalogMessageNames.COURSE_PUBLISHED)
                               .hasMessageContaining(CourseCatalogMessageNames.STUDENT_REGISTERED));
        }

        @Test
        void bumpingTheVersionViaPredicateIsAllowed() {
            // given: a predicate that matches only CoursePublished v1 and bumps it to v2 (the
            // exact-equality predicate keeps the v2 output from re-matching and looping)
            MessageType coursePublishedV1 = new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "1.0.0");
            EventTransformation structuralUpcast =
                    EventTransformation.from(type -> type.equals(coursePublishedV1))
                                       .to(new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "2.0.0"))
                                       .transform(JsonNode.class, (node, ctx) -> node);
            EventTransformerChain chain = EventTransformerChain.builder()
                                                               .register(structuralUpcast)
                                                               .build();

            // when / then: the version bump is applied, the qualified name is preserved
            ChainTester.forChain(chain)
                       .given()
                       .messageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "1.0.0")
                       .payload(JsonNodeFactory.instance.objectNode())
                       .when()
                       .then()
                       .outputType(new MessageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "2.0.0"));
        }
    }
}

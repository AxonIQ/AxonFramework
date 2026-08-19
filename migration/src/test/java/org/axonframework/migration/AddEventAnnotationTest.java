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

package org.axonframework.migration;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies the {@link AddEventAnnotation} recipe migrates {@code @Revision("x")} on AF4 event
 * payload classes to a class-level {@code @Event(version = "x")} on AF5, removing the original
 * {@code @Revision} annotation and its import.
 */
class AddEventAnnotationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddEventAnnotation())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void migratesRevisionToEventVersionOnRecord() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.serialization.Revision;

                        @Revision("0.0.1")
                        public record PaymentPreparedEvent(String paymentId, int amount) {
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.messaging.eventhandling.annotation.Event;

                        @Event(version = "0.0.1")
                        public record PaymentPreparedEvent(String paymentId, int amount) {
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.EventSourcingHandler;

                        class Projection {
                            @EventSourcingHandler
                            void on(PaymentPreparedEvent event) {
                            }
                        }
                        """
                )
        );
    }

    @Test
    void migratesRevisionToEventVersionOnClass() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.serialization.Revision;

                        @Revision("1.2")
                        public class BikeRegisteredEvent {
                            private String bikeId;
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.messaging.eventhandling.annotation.Event;

                        @Event(version = "1.2")
                        public class BikeRegisteredEvent {
                            private String bikeId;
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.eventsourcing.EventSourcingHandler;

                        class Projection {
                            @EventSourcingHandler
                            void on(BikeRegisteredEvent event) {
                            }
                        }
                        """
                )
        );
    }
}

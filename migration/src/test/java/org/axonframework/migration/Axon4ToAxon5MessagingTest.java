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
import org.openrewrite.config.Environment;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies the messaging-module migration: handler annotations move into
 * {@code .annotation.*} subpackages, and {@code EventBus} renames to
 * {@code EventSink}.
 */
class Axon4ToAxon5MessagingTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                            .scanRuntimeClasspath("org.axonframework.migration")
                            .build()
                            .activateRecipes(
                                    "org.axonframework.migration.Axon4ToAxon5Messaging"));
    }

    @Test
    void renamesCommandHandlerAnnotationIntoAnnotationSubpackage() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.commandhandling.CommandHandler;
                        class Foo {
                            @CommandHandler
                            void handle(Object cmd) {}
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

                        class Foo {
                            @CommandHandler
                            void handle(Object cmd) {}
                        }
                        """
                )
        );
    }

    @Test
    void renamesEventBusToEventSink() {
        rewriteRun(
                java(
                        """
                        package com.example;
                        import org.axonframework.eventhandling.EventBus;
                        class Foo {
                            EventBus bus;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.eventhandling.EventSink;

                        class Foo {
                            EventSink bus;
                        }
                        """
                )
        );
    }
}

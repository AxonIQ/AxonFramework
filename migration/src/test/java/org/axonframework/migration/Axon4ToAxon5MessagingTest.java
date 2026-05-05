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

    // ── RemoveTypeArguments: AF5 message types lost their <T> payload parameter ─

    @Test
    void stripsPayloadTypeArgumentFromEventMessage() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.eventhandling.EventMessage;

                        class Foo {
                            EventMessage<String> typed;
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.eventhandling.EventMessage;

                        class Foo {
                            EventMessage typed;
                        }
                        """
                )
        );
    }

    @Test
    void stripsWildcardTypeArgumentFromCommandMessageNestedInsideMessageHandlerInterceptor() {
        // Verifies the recipe descends into nested ParameterizedType nodes — the
        // outer MessageHandlerInterceptor stays generic on its M parameter, but
        // the inner CommandMessage<?> gets de-parameterized.
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.commandhandling.CommandMessage;
                        import org.axonframework.messaging.MessageHandlerInterceptor;

                        class Foo implements MessageHandlerInterceptor<CommandMessage<?>> {}
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.commandhandling.CommandMessage;
                        import org.axonframework.messaging.core.MessageHandlerInterceptor;

                        class Foo implements MessageHandlerInterceptor<CommandMessage> {}
                        """
                )
        );
    }

    @Test
    void stripsBoundedWildcardTypeArgumentFromUnitOfWork() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.commandhandling.CommandMessage;
                        import org.axonframework.messaging.unitofwork.UnitOfWork;

                        class Foo {
                            void m(UnitOfWork<? extends CommandMessage<?>> uow) {}
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.commandhandling.CommandMessage;
                        import org.axonframework.messaging.core.unitofwork.UnitOfWork;

                        class Foo {
                            void m(UnitOfWork uow) {}
                        }
                        """
                )
        );
    }

    @Test
    void leavesUnrelatedGenericTypesParameterized() {
        // RemoveTypeArguments must only fire on the configured AF5 message types;
        // user generics and standard JDK generics stay intact.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import java.util.List;
                        class Container<T> {
                            List<String> items;
                            Container<Integer> nested;
                        }
                        """
                )
        );
    }

    // ── ChangeMethodName: AF5 dropped `get` prefix and `MetaData` casing ────

    @Test
    void renamesGetIdentifierToIdentifier() {
        // matchOverrides: true means calls on EventMessage instances also rename,
        // even though only the Message-level method pattern is configured.
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.eventhandling.EventMessage;

                        class Foo {
                            String name(EventMessage<?> e) { return e.getIdentifier(); }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.eventhandling.EventMessage;

                        class Foo {
                            String name(EventMessage e) { return e.identifier(); }
                        }
                        """
                )
        );
    }

    @Test
    void renamesGetMetaDataToMetadata() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.messaging.Message;
                        import org.axonframework.messaging.MetaData;

                        class Foo {
                            MetaData get(Message<?> m) { return m.getMetaData(); }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.core.Message;
                        import org.axonframework.messaging.core.Metadata;

                        class Foo {
                            Metadata get(Message m) { return m.metadata(); }
                        }
                        """
                )
        );
    }

    @Test
    void renamesGetPayloadAndGetPayloadTypeOnEventMessageOverride() {
        // EventMessage overrides Message; matchOverrides:true must rewrite
        // call-sites typed against EventMessage as well.
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.eventhandling.EventMessage;

                        class Foo {
                            Object get(EventMessage<?> e) { return e.getPayload(); }
                            Class<?> type(EventMessage<?> e) { return e.getPayloadType(); }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.messaging.eventhandling.EventMessage;

                        class Foo {
                            Object get(EventMessage e) { return e.payload(); }
                            Class<?> type(EventMessage e) { return e.payloadType(); }
                        }
                        """
                )
        );
    }

    @Test
    void renamesWithMetaDataAndAndMetaData() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import java.util.Map;
                        import org.axonframework.eventhandling.EventMessage;

                        class Foo {
                            EventMessage<?> chain(EventMessage<?> e, Map<String, ?> md) {
                                return e.withMetaData(md).andMetaData(md);
                            }
                        }
                        """,
                        """
                        package com.example;

                        import java.util.Map;
                        import org.axonframework.messaging.eventhandling.EventMessage;

                        class Foo {
                            EventMessage chain(EventMessage e, Map<String, ?> md) {
                                return e.withMetadata(md).andMetadata(md);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void renamesGetTimestampOnEventMessage() {
        // getTimestamp is EventMessage-specific (not on Message); needs its own
        // ChangeMethodName rule against the EventMessage type pattern.
        rewriteRun(
                java(
                        """
                        package com.example;

                        import java.time.Instant;
                        import org.axonframework.eventhandling.EventMessage;

                        class Foo {
                            Instant when(EventMessage<?> e) { return e.getTimestamp(); }
                        }
                        """,
                        """
                        package com.example;

                        import java.time.Instant;
                        import org.axonframework.messaging.eventhandling.EventMessage;

                        class Foo {
                            Instant when(EventMessage e) { return e.timestamp(); }
                        }
                        """
                )
        );
    }
}

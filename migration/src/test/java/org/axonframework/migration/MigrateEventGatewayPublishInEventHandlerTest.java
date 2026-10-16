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

import org.junit.jupiter.api.*;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.kotlin.Assertions.kotlin;

class MigrateEventGatewayPublishInEventHandlerTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateEventGatewayPublishInEventHandler())
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class JavaHandlers {

        @Test
        void addsTheContextParameterAndPassesItFirst() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.eventhandling.gateway.EventGateway;

                            class StockService {
                                private final EventGateway events;

                                StockService(EventGateway events) {
                                    this.events = events;
                                }

                                @CommandHandler
                                public void handle(Object cmd) {
                                    events.publish(new Object());
                                    this.events.publish(new Object(), new Object());
                                }

                                public void notAHandler() {
                                    events.publish(new Object());
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.eventhandling.gateway.EventGateway;
                            import org.axonframework.messaging.core.unitofwork.ProcessingContext;

                            class StockService {
                                private final EventGateway events;

                                StockService(EventGateway events) {
                                    this.events = events;
                                }

                                @CommandHandler
                                public void handle(Object cmd, ProcessingContext context) {
                                    events.publish(context, new Object());
                                    this.events.publish(context, new Object(), new Object());
                                }

                                public void notAHandler() {
                                    events.publish(new Object());
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void reusesAnExistingProcessingContextParameter() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.eventhandling.EventHandler;
                            import org.axonframework.eventhandling.gateway.EventGateway;
                            import org.axonframework.messaging.core.unitofwork.ProcessingContext;

                            class Projector {
                                private EventGateway gateway;

                                @EventHandler
                                public void on(Object event, ProcessingContext processingContext) {
                                    gateway.publish(new Object());
                                    gateway.publish(processingContext, new Object());
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.eventhandling.EventHandler;
                            import org.axonframework.eventhandling.gateway.EventGateway;
                            import org.axonframework.messaging.core.unitofwork.ProcessingContext;

                            class Projector {
                                private EventGateway gateway;

                                @EventHandler
                                public void on(Object event, ProcessingContext processingContext) {
                                    gateway.publish(processingContext, new Object());
                                    gateway.publish(processingContext, new Object());
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void picksAFreeNameWhenAParameterIsAlreadyCalledContext() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.eventhandling.gateway.EventGateway;

                            class StockService {
                                private EventGateway events;

                                @CommandHandler
                                public void handle(Object cmd, String context) {
                                    events.publish(context);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.eventhandling.gateway.EventGateway;
                            import org.axonframework.messaging.core.unitofwork.ProcessingContext;

                            class StockService {
                                private EventGateway events;

                                @CommandHandler
                                public void handle(Object cmd, String context, ProcessingContext context1) {
                                    events.publish(context1, context);
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesHandlersWithoutAGatewayAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;

                            class Plain {
                                @CommandHandler
                                public void handle(Object cmd) {
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class JavaPrivateHelpers {

        @Test
        void passesTheContextIntoPrivateHelpersThatPublish() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.eventhandling.gateway.EventGateway;

                            class StockService {
                                private EventGateway events;

                                @CommandHandler
                                public void handle(Object cmd) {
                                    reserve("s-1");
                                }

                                private void reserve(String stockId) {
                                    notifyReserved(stockId);
                                }

                                private void notifyReserved(String stockId) {
                                    events.publish(stockId);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.eventhandling.gateway.EventGateway;
                            import org.axonframework.messaging.core.unitofwork.ProcessingContext;

                            class StockService {
                                private EventGateway events;

                                @CommandHandler
                                public void handle(Object cmd, ProcessingContext context) {
                                    reserve("s-1", context);
                                }

                                private void reserve(String stockId, ProcessingContext context) {
                                    notifyReserved(stockId, context);
                                }

                                private void notifyReserved(String stockId, ProcessingContext context) {
                                    events.publish(context, stockId);
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesHelpersNotReachedFromAHandlerAlone() {
            // Neither a public method nor a private helper only it calls has a context to pass on.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.eventhandling.gateway.EventGateway;

                            class StockService {
                                private EventGateway events;

                                public void scheduled() {
                                    announce("s-1");
                                }

                                private void announce(String stockId) {
                                    events.publish(stockId);
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class KotlinSources {

        @Test
        void migratesHandlersAndPrivateHelpers() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.commandhandling.CommandHandler
                            import org.axonframework.eventhandling.gateway.EventGateway

                            class StockService {
                                private lateinit var events: EventGateway

                                @CommandHandler
                                fun handle(cmd: Any) {
                                    events.publish(cmd)
                                    reserve("s-1")
                                }

                                private fun reserve(stockId: String) {
                                    events.publish(stockId)
                                }
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.commandhandling.CommandHandler
                            import org.axonframework.eventhandling.gateway.EventGateway
                            import org.axonframework.messaging.core.unitofwork.ProcessingContext

                            class StockService {
                                private lateinit var events: EventGateway

                                @CommandHandler
                                fun handle(cmd: Any, context: ProcessingContext) {
                                    events.publish(context, cmd)
                                    reserve("s-1", context)
                                }

                                private fun reserve(stockId: String, context: ProcessingContext) {
                                    events.publish(context, stockId)
                                }
                            }
                            """
                    )
            );
        }
    }
}

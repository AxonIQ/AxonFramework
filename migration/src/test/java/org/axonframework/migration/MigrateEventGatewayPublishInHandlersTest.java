package org.axonframework.migration;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class MigrateEventGatewayPublishInHandlersTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateEventGatewayPublishInHandlers())
            .typeValidationOptions(TypeValidation.none());
    }

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

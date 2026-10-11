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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.PrintOutputCapture;
import org.openrewrite.config.Environment;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.kotlin.Assertions.kotlin;
import static org.openrewrite.kotlin.Assertions.srcMainKotlin;
import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Verifies the deadline usage of Axon Framework 4 aggregates and Sagas through the whole
 * {@code UpgradeAxon4ToAxoniq5} upgrade, including the free Axon Framework 5 leg that runs after the legacy recipes.
 */
class Axon4ToAxoniq5LegacyDeadlineAggregateTest implements RewriteTest {

    private static final String POM = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>order</artifactId>
                <version>1.0.0</version>
            </project>
            """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                               .scanRuntimeClasspath("org.axonframework.migration")
                               .build()
                               .activateRecipes("io.axoniq.framework.migration.UpgradeAxon4ToAxoniq5"))
            .typeValidationOptions(TypeValidation.none())
            .markerPrinter(PrintOutputCapture.MarkerPrinter.SEARCH_MARKERS_ONLY);
    }

    @Nested
    class Aggregates {

        private static final String PAYMENT_DEADLINE = """
                package com.example;

                public class PaymentDeadline {
                    private final String orderId;

                    public PaymentDeadline(String orderId) {
                        this.orderId = orderId;
                    }

                    public String getOrderId() {
                        return orderId;
                    }
                }
                """;

        private static final String ORDER = """
                package com.example;

                import org.axonframework.commandhandling.CommandHandler;
                import org.axonframework.deadline.DeadlineManager;
                import org.axonframework.deadline.annotation.DeadlineHandler;
                import org.axonframework.eventsourcing.EventSourcingHandler;
                import org.axonframework.modelling.command.AggregateIdentifier;
                import org.axonframework.spring.stereotype.Aggregate;

                import java.time.Duration;

                import static org.axonframework.modelling.command.AggregateLifecycle.apply;

                @Aggregate
                public class Order {

                    @AggregateIdentifier
                    private String orderId;

                    protected Order() {
                    }

                    @CommandHandler
                    public void handle(ConfirmOrder command, DeadlineManager deadlineManager) {
                        deadlineManager.schedule(Duration.ofDays(1), "paymentDeadline", new PaymentDeadline(orderId));
                        deadlineManager.schedule(Duration.ofHours(1), "reminder");
                    }

                    @CommandHandler
                    public void handle(PayOrder command, DeadlineManager deadlineManager) {
                        deadlineManager.cancelAllWithinScope("paymentDeadline");
                    }

                    @DeadlineHandler(deadlineName = "paymentDeadline")
                    public void on(PaymentDeadline deadline) {
                        apply(new OrderCancelled(orderId));
                    }

                    @DeadlineHandler(deadlineName = "reminder")
                    public void onReminder() {
                        apply(new ReminderSent(orderId));
                    }

                    @EventSourcingHandler
                    public void on(OrderCancelled event) {
                    }
                }
                """;

        @Test
        void migratesDeadlineHandlersAndScopelessCallsOfEventSourcedAggregate() {
            rewriteRun(
                    // The free leg moves org.axonframework.modelling.command to modelling.entity in every cycle, and
                    // UpgradeAxon4ToAxoniq5 moves AggregateScopeDescriptor back each time, so a second cycle
                    // rewrites that import again to the same result.
                    spec -> spec.expectedCyclesThatMakeChanges(2),
                    mavenProject(
                            "order",
                            pomXml(POM, pom -> pom.after(actual -> {
                                assertThat(actual).contains("<artifactId>axoniq-legacy</artifactId>");
                                return actual;
                            })),
                            // The commands and events get their Axon Framework 5 annotations, which this test
                            // does not inspect.
                            srcMainJava(java(record("ConfirmOrder"), source -> source.after(actual -> actual))),
                            srcMainJava(java(record("PayOrder"), source -> source.after(actual -> actual))),
                            srcMainJava(java(record("OrderCancelled"), source -> source.after(actual -> actual))),
                            srcMainJava(java(record("ReminderSent"), source -> source.after(actual -> actual))),
                            // The deadline payload class needs no @TargetEntityId: the entity is resolved from the
                            // deadline's aggregate scope.
                            srcMainJava(java(PAYMENT_DEADLINE)),
                            srcMainJava(java(ORDER, source -> source.after(actual -> {
                                assertMigratedOrder(actual);
                                return actual;
                            })))
                    )
            );
        }

        private static void assertMigratedOrder(String actual) {
            String scope = "new AggregateScopeDescriptor(\"Order\", orderId)";
            assertThat(actual)
                    .contains("import org.axonframework.messaging.commandhandling.annotation.CommandHandler;")
                    .contains("import org.axonframework.messaging.eventhandling.gateway.EventAppender;")
                    .contains("import org.axonframework.modelling.command.AggregateScopeDescriptor;")
                    .contains("import org.axonframework.deadline.DeadlineManager;")
                    .contains("deadlineManager.schedule(Duration.ofDays(1), \"paymentDeadline\", "
                                      + "new PaymentDeadline(orderId), " + scope + ");")
                    .contains("deadlineManager.schedule(Duration.ofHours(1), \"reminder\", null, " + scope + ");")
                    .contains("deadlineManager.cancelAllWithinScope(\"paymentDeadline\", " + scope + ");")
                    .contains("""
                                      @CommandHandler
                                          public void on(PaymentDeadline deadline, EventAppender eventAppender) {
                                              eventAppender.append(new OrderCancelled(orderId));""")
                    .contains("""
                                      @CommandHandler(commandName = "reminder")
                                          public void onReminder(EventAppender eventAppender) {
                                              eventAppender.append(new ReminderSent(orderId));""")
                    .doesNotContain("DeadlineHandler")
                    .doesNotContain("TODO(axon4to5)")
                    .doesNotContain("org.axonframework.modelling.entity.AggregateScopeDescriptor");
        }

        @Test
        void marksScopelessCallInCreationCommandHandler() {
            rewriteRun(
                    mavenProject(
                            "order",
                            pomXml(POM, pom -> pom.after(actual -> actual)),
                            srcMainJava(java(record("PlaceOrder"), source -> source.after(actual -> actual))),
                            srcMainJava(java(
                                    """
                                    package com.example;

                                    import org.axonframework.commandhandling.CommandHandler;
                                    import org.axonframework.deadline.DeadlineManager;
                                    import org.axonframework.modelling.command.AggregateIdentifier;
                                    import org.axonframework.spring.stereotype.Aggregate;

                                    import java.time.Duration;

                                    @Aggregate
                                    public class Order {

                                        @AggregateIdentifier
                                        private String orderId;

                                        protected Order() {
                                        }

                                        @CommandHandler
                                        public Order(PlaceOrder command, DeadlineManager deadlineManager) {
                                            deadlineManager.schedule(Duration.ofDays(1), "paymentDeadline");
                                        }
                                    }
                                    """,
                                    source -> source.after(actual -> {
                                        assertThat(actual)
                                                .contains("""
                                                                  // TODO(axon4to5): pass the aggregate scope to this \
                                                                  DeadlineManager call, as in \
                                                                  new AggregateScopeDescriptor("Order", \
                                                                  <aggregate identifier>).""")
                                                .contains("deadlineManager.schedule(Duration.ofDays(1), "
                                                                  + "\"paymentDeadline\");");
                                        return actual;
                                    })
                            ))
                    )
            );
        }
    }

    @Nested
    class Sagas {

        @Test
        void sagaKeepsDeadlineHandlerAndScopeTypesInTheirAxonFramework4Packages() {
            // The scope types keep their Axon Framework 4 packages in axoniq-legacy, so no package-wide rule of the
            // upgrade may move them.
            rewriteRun(
                    mavenProject(
                            "order",
                            pomXml(POM, pom -> pom.after(actual -> {
                                assertThat(actual).contains("<artifactId>axoniq-legacy</artifactId>");
                                return actual;
                            })),
                            srcMainJava(java(
                                    """
                                    package com.example;

                                    import org.axonframework.deadline.DeadlineManager;
                                    import org.axonframework.deadline.annotation.DeadlineHandler;
                                    import org.axonframework.messaging.Scope;
                                    import org.axonframework.messaging.ScopeDescriptor;
                                    import org.axonframework.modelling.saga.SagaEventHandler;

                                    import java.time.Duration;

                                    public class PaymentSaga {

                                        private String orderId;

                                        @SagaEventHandler(associationProperty = "orderId")
                                        public void on(String event, DeadlineManager deadlineManager) {
                                            ScopeDescriptor scope = Scope.describeCurrentScope();
                                            deadlineManager.schedule(Duration.ofDays(1), "payment", null, scope);
                                        }

                                        @DeadlineHandler(deadlineName = "payment")
                                        public void onPayment(ScopeDescriptor scope, DeadlineManager deadlines) {
                                            deadlines.cancelAllWithinScope("payment", scope);
                                        }
                                    }
                                    """
                            ))
                    )
            );
        }

        @Test
        void existingHandlerParametersKeepTheirTypesWhenTheDeadlineManagerFieldMovesIntoThem() {
            // The moved field is appended after the existing parameters. Those must stay attributed, so the free
            // leg still renames MetaData, moves EventMessage, and drops the DeadlineMessage type argument.
            rewriteRun(
                    mavenProject(
                            "order",
                            pomXml(POM, pom -> pom.after(actual -> actual)),
                            srcMainJava(java(
                                    """
                                    package org.springframework.beans.factory.annotation;

                                    public @interface Autowired {
                                    }
                                    """
                            )),
                            srcMainJava(java(
                                    """
                                    package com.example;

                                    import org.axonframework.deadline.DeadlineManager;
                                    import org.axonframework.deadline.DeadlineMessage;
                                    import org.axonframework.deadline.annotation.DeadlineHandler;
                                    import org.axonframework.eventhandling.EventMessage;
                                    import org.axonframework.messaging.MetaData;
                                    import org.axonframework.messaging.annotation.MetaDataValue;
                                    import org.axonframework.modelling.saga.SagaEventHandler;
                                    import org.springframework.beans.factory.annotation.Autowired;

                                    import java.time.Duration;

                                    public class PaymentSaga {

                                        @Autowired
                                        private transient DeadlineManager deadlineManager;

                                        @SagaEventHandler(associationProperty = "orderId")
                                        public void on(String event, MetaData metaData, @MetaDataValue("k") String k,
                                                       EventMessage<?> message) {
                                            deadlineManager.schedule(Duration.ofDays(1), "payment");
                                        }

                                        @DeadlineHandler(deadlineName = "payment")
                                        public void onDeadline(DeadlineMessage<?> msg) {
                                            deadlineManager.cancelAll("payment");
                                        }
                                    }
                                    """,
                                    source -> source.after(actual -> {
                                        assertThat(actual)
                                                .contains("import org.axonframework.messaging.core.Metadata;")
                                                .contains("import org.axonframework.messaging.core.annotation"
                                                                  + ".MetadataValue;")
                                                .contains("import org.axonframework.messaging.eventhandling"
                                                                  + ".EventMessage;")
                                                .contains("public void on(String event, Metadata metaData, "
                                                                  + "@MetadataValue(\"k\") String k,\n"
                                                                  + "                   EventMessage message, "
                                                                  + "DeadlineManager deadlineManager) {")
                                                .contains("public void onDeadline(DeadlineMessage msg, "
                                                                  + "DeadlineManager deadlineManager) {")
                                                .doesNotContain("MetaData ")
                                                .doesNotContain("<?>");
                                        return actual;
                                    })
                            ))
                    )
            );
        }

        @Test
        void existingKotlinHandlerParametersKeepTheirTypesWhenTheDeadlineManagerPropertyMovesIntoThem() {
            rewriteRun(
                    mavenProject(
                            "order",
                            pomXml(POM, pom -> pom.after(actual -> actual)),
                            srcMainKotlin(kotlin(
                                    """
                                    package com.example

                                    import org.axonframework.deadline.DeadlineManager
                                    import org.axonframework.messaging.MetaData
                                    import org.axonframework.modelling.saga.SagaEventHandler

                                    class PaymentSaga {

                                        private lateinit var deadlineManager: DeadlineManager

                                        @SagaEventHandler(associationProperty = "orderId")
                                        fun on(event: String, metaData: MetaData) {
                                            deadlineManager.cancelAll(event)
                                        }
                                    }
                                    """,
                                    source -> source.after(actual -> {
                                        assertThat(actual)
                                                .contains("import org.axonframework.messaging.core.Metadata")
                                                .contains("fun on(event: String, metaData: Metadata, "
                                                                  + "deadlineManager: DeadlineManager) {")
                                                .doesNotContain("MetaData");
                                        return actual;
                                    })
                            ))
                    )
            );
        }
    }

    private static String record(String name) {
        return "package com.example;\n\npublic record " + name + "(String orderId) {\n}\n";
    }
}

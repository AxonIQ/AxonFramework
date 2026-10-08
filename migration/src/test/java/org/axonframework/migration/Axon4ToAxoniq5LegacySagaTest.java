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
import org.openrewrite.Recipe;
import org.openrewrite.config.Environment;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.java.Assertions.srcTestJava;
import static org.openrewrite.kotlin.Assertions.kotlin;
import static org.openrewrite.kotlin.Assertions.srcMainKotlin;
import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Verifies migration of Axon Framework 4 Sagas onto the {@code io.axoniq.framework:axoniq-legacy} compatibility module.
 */
class Axon4ToAxoniq5LegacySagaTest implements RewriteTest {

    private static final String AXONIQ_VERSION = axoniqVersion();

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                               .scanRuntimeClasspath("org.axonframework.migration")
                               .build()
                               .activateRecipes("io.axoniq.framework.migration.Axon4ToAxoniq5LegacySaga"))
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class SagaLifecycleMigration {

        @Test
        void replacesStaticLifecycleCallsWithInjectedParameter() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object event) {
                                    SagaLifecycle.associateWith("paymentId", "payment");
                                    SagaLifecycle.removeAssociationWith("rentalId", "rental");
                                    if (SagaLifecycle.associationValues().isEmpty()) {
                                        SagaLifecycle.end();
                                    }
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object event, SagaLifecycle sagaLifecycle) {
                                    sagaLifecycle.associateWith("paymentId", "payment");
                                    sagaLifecycle.removeAssociationWith("rentalId", "rental");
                                    if (sagaLifecycle.associationValues().isEmpty()) {
                                        sagaLifecycle.end();
                                    }
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void usesExistingLifecycleParameter() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object event, SagaLifecycle lifecycle) {
                                    SagaLifecycle.end();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.axonframework.modelling.saga.SagaLifecycle;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object event, SagaLifecycle lifecycle) {
                                    lifecycle.end();
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class CommandDispatchMigration {

        @Test
        void keepsSendFireAndForgetAndSendAndWaitSynchronous() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.gateway.CommandGateway;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            class PaymentSaga {
                                @Autowired
                                private transient CommandGateway commandGateway;

                                @SagaEventHandler(associationProperty = "rentalId")
                                void prepare(Object command) {
                                    commandGateway.send(command);
                                }

                                @SagaEventHandler(associationProperty = "paymentId")
                                void confirm(Object command) {
                                    commandGateway.sendAndWait(command);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.common.FutureUtils;
                            import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                void prepare(Object command, CommandDispatcher commandDispatcher) {
                                    commandDispatcher.send(command);
                                }

                                @SagaEventHandler(associationProperty = "paymentId")
                                void confirm(Object command, CommandDispatcher commandDispatcher) {
                                    FutureUtils.joinAndUnwrap(commandDispatcher.send(command).getResultMessage());
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesRegularEventHandlerToTheGeneralRecipe() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.gateway.CommandGateway;
                            import org.axonframework.eventhandling.EventHandler;

                            class Projection {
                                private final CommandGateway commandGateway;

                                Projection(CommandGateway commandGateway) {
                                    this.commandGateway = commandGateway;
                                }

                                @EventHandler
                                void on(Object event) {
                                    commandGateway.sendAndWait(event);
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class ResourceInjectionMigration {

        @Test
        void migratesTheGatewayAndMarksTheOtherInjectedField() {
            // The CommandGateway field becomes a handler parameter, so only the remaining injected field is marked.
            // The @Autowired stub resolves the annotation type, as Spring on a real classpath would; unresolved, the
            // gateway migration would drop the import the remaining field still needs.
            rewriteRun(
                    java(
                            """
                            package org.springframework.beans.factory.annotation;

                            public @interface Autowired {
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.gateway.CommandGateway;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            class PaymentSaga {
                                @Autowired
                                private transient CommandGateway commandGateway;
                                @Autowired
                                private transient PaymentService paymentService;

                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object command) {
                                    paymentService.charge(command);
                                    commandGateway.send(command);
                                }
                            }
                            interface PaymentService { void charge(Object command); }
                            """,
                            """
                            package com.example;

                            import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            class PaymentSaga {
                                // TODO(axon4to5): add this dependency as a parameter of each @SagaEventHandler method that uses it, then remove the field. Axon Framework 5 does not inject Saga fields.
                                @Autowired
                                private transient PaymentService paymentService;

                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object command, CommandDispatcher commandDispatcher) {
                                    paymentService.charge(command);
                                    commandDispatcher.send(command);
                                }
                            }
                            interface PaymentService { void charge(Object command); }
                            """
                    )
            );
        }
    }

    @Nested
    class KotlinSagaMigration {

        @Test
        void migratesLifecycleAndCommandDispatchParameters() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.commandhandling.gateway.CommandGateway
                            import org.axonframework.modelling.saga.SagaEventHandler
                            import org.axonframework.modelling.saga.SagaLifecycle

                            class PaymentSaga {
                                private lateinit var commandGateway: CommandGateway

                                @SagaEventHandler(associationProperty = "rentalId")
                                fun prepare(command: Any) {
                                    SagaLifecycle.associateWith("paymentId", "payment")
                                    commandGateway.send(command)
                                }

                                @SagaEventHandler(associationProperty = "paymentId")
                                fun confirm(command: Any) {
                                    commandGateway.sendAndWait<Any>(command)
                                }
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.common.FutureUtils
                            import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher
                            import org.axonframework.modelling.saga.SagaEventHandler
                            import org.axonframework.modelling.saga.SagaLifecycle

                            class PaymentSaga {
                                @SagaEventHandler(associationProperty = "rentalId")
                                fun prepare(command: Any, sagaLifecycle: SagaLifecycle, commandDispatcher: CommandDispatcher) {
                                    sagaLifecycle.associateWith("paymentId", "payment")
                                    commandDispatcher.send(command)
                                }

                                @SagaEventHandler(associationProperty = "paymentId")
                                fun confirm(command: Any, commandDispatcher: CommandDispatcher) {
                                    FutureUtils.joinAndUnwrap(commandDispatcher.send(command).getResultMessage())
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void passesTheDispatcherIntoPrivateHelpersThatUseTheGateway() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.commandhandling.gateway.CommandGateway
                            import org.axonframework.modelling.saga.SagaEventHandler

                            class OrderSaga {
                                private lateinit var commandGateway: CommandGateway

                                @SagaEventHandler(associationProperty = "orderId")
                                fun on(event: Any) {
                                    proceed("o-1")
                                }

                                private fun proceed(orderId: String) {
                                    commandGateway.send(orderId)
                                    compensate(orderId)
                                }

                                private fun compensate(orderId: String) {
                                    commandGateway.sendAndWait<Any>(orderId)
                                }
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.common.FutureUtils
                            import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher
                            import org.axonframework.modelling.saga.SagaEventHandler

                            class OrderSaga {
                                @SagaEventHandler(associationProperty = "orderId")
                                fun on(event: Any, commandDispatcher: CommandDispatcher) {
                                    proceed("o-1", commandDispatcher)
                                }

                                private fun proceed(orderId: String, commandDispatcher: CommandDispatcher) {
                                    commandDispatcher.send(orderId)
                                    compensate(orderId, commandDispatcher)
                                }

                                private fun compensate(orderId: String, commandDispatcher: CommandDispatcher) {
                                    FutureUtils.joinAndUnwrap(commandDispatcher.send(orderId).getResultMessage())
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class DependencyMigration {

        @Test
        void addsAxoniqLegacyWhenSagaSourceIsPresent() {
            rewriteRun(
                    Axon4ToAxoniq5LegacySagaTest::ignoreUnpublishedTargetVersionWarning,
                    mavenProject(
                            "rental",
                            pomXml(
                            """
                            <project>
                                <modelVersion>4.0.0</modelVersion>
                                <groupId>com.example</groupId>
                                <artifactId>rental</artifactId>
                                <version>1.0.0</version>
                            </project>
                            """,
                            """
                            <project>
                                <modelVersion>4.0.0</modelVersion>
                                <groupId>com.example</groupId>
                                <artifactId>rental</artifactId>
                                <version>1.0.0</version>
                                <dependencies>
                                    <dependency>
                                        <groupId>io.axoniq.framework</groupId>
                                        <artifactId>axoniq-legacy</artifactId>
                                        <version>%s</version>
                                    </dependency>
                                </dependencies>
                            </project>
                            """.formatted(AXONIQ_VERSION)
                            ),
                            srcMainJava(
                                    java(
                                            """
                                            package com.example;

                                            import org.axonframework.modelling.saga.SagaEventHandler;

                                            class PaymentSaga {
                                                @SagaEventHandler(associationProperty = "rentalId")
                                                void on(Object event) {
                                                }
                                            }
                                            """
                                    )
                            )
                    )
            );
        }

        @Test
        void addsAxoniqLegacyForKotlinSagaSource() {
            rewriteRun(
                    Axon4ToAxoniq5LegacySagaTest::ignoreUnpublishedTargetVersionWarning,
                    mavenProject(
                            "rental",
                            pomXml(
                                    """
                                    <project>
                                        <modelVersion>4.0.0</modelVersion>
                                        <groupId>com.example</groupId>
                                        <artifactId>rental</artifactId>
                                        <version>1.0.0</version>
                                    </project>
                                    """,
                                    """
                                    <project>
                                        <modelVersion>4.0.0</modelVersion>
                                        <groupId>com.example</groupId>
                                        <artifactId>rental</artifactId>
                                        <version>1.0.0</version>
                                        <dependencies>
                                            <dependency>
                                                <groupId>io.axoniq.framework</groupId>
                                                <artifactId>axoniq-legacy</artifactId>
                                                <version>%s</version>
                                            </dependency>
                                        </dependencies>
                                    </project>
                                    """.formatted(AXONIQ_VERSION)
                            ),
                            srcMainKotlin(
                                    kotlin(
                                            """
                                            package com.example

                                            import org.axonframework.spring.stereotype.Saga

                                            @Saga
                                            class PaymentSaga
                                            """
                                    )
                            )
                    )
            );
        }

        @Test
        void doesNotAddAxoniqLegacyWithoutSagaSource() {
            rewriteRun(
                    mavenProject(
                            "rental",
                            pomXml(
                                    """
                                    <project>
                                        <modelVersion>4.0.0</modelVersion>
                                        <groupId>com.example</groupId>
                                        <artifactId>rental</artifactId>
                                        <version>1.0.0</version>
                                    </project>
                                    """
                            ),
                            srcMainJava(
                                    java(
                                            """
                                            package com.example;

                                            class Projection {
                                                void on(Object event) {
                                                }
                                            }
                                            """
                                    )
                            )
                    )
            );
        }
    }

    @Nested
    class PrivateHelperMigration {

        @Test
        void passesTheDispatcherIntoPrivateHelpersThatUseTheGateway() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.gateway.CommandGateway;
                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            class OrderSaga {
                                @Autowired
                                private transient CommandGateway commandGateway;
                                private boolean paid;

                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event) {
                                    paid = true;
                                    proceed("o-1");
                                }

                                @SagaEventHandler(associationProperty = "orderId")
                                void onFailure(Object event) {
                                    compensate("o-1", "failed");
                                }

                                private void proceed(String orderId) {
                                    if (paid) {
                                        commandGateway.send(new Object());
                                    }
                                }

                                private void compensate(String orderId, String reason) {
                                    commandGateway.sendAndWait(new Object());
                                    proceed(orderId);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.common.FutureUtils;
                            import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
                            import org.axonframework.modelling.saga.SagaEventHandler;

                            class OrderSaga {
                                private boolean paid;

                                @SagaEventHandler(associationProperty = "orderId")
                                void on(Object event, CommandDispatcher commandDispatcher) {
                                    paid = true;
                                    proceed("o-1", commandDispatcher);
                                }

                                @SagaEventHandler(associationProperty = "orderId")
                                void onFailure(Object event, CommandDispatcher commandDispatcher) {
                                    compensate("o-1", "failed", commandDispatcher);
                                }

                                private void proceed(String orderId, CommandDispatcher commandDispatcher) {
                                    if (paid) {
                                        commandDispatcher.send(new Object());
                                    }
                                }

                                private void compensate(String orderId, String reason, CommandDispatcher commandDispatcher) {
                                    FutureUtils.joinAndUnwrap(commandDispatcher.send(new Object()).getResultMessage());
                                    proceed(orderId, commandDispatcher);
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class SagaTestFixtureMigration {

        @Test
        void keepsSagaTestFixtureAddsAxoniqLegacyTestAndATearDown() {
            rewriteRun(
                    Axon4ToAxoniq5LegacySagaTest::ignoreUnpublishedTargetVersionWarning,
                    mavenProject(
                            "rental",
                            pomXml(
                            """
                            <project>
                                <modelVersion>4.0.0</modelVersion>
                                <groupId>com.example</groupId>
                                <artifactId>rental</artifactId>
                                <version>1.0.0</version>
                            </project>
                            """,
                            """
                            <project>
                                <modelVersion>4.0.0</modelVersion>
                                <groupId>com.example</groupId>
                                <artifactId>rental</artifactId>
                                <version>1.0.0</version>
                                <dependencies>
                                    <dependency>
                                        <groupId>io.axoniq.framework</groupId>
                                        <artifactId>axoniq-legacy-test</artifactId>
                                        <version>%s</version>
                                        <scope>test</scope>
                                    </dependency>
                                </dependencies>
                            </project>
                            """.formatted(AXONIQ_VERSION)
                            ),
                            srcTestJava(
                                    java(
                                            """
                                            package com.example;

                                            import org.axonframework.test.saga.SagaTestFixture;
                                            import org.junit.jupiter.api.BeforeEach;

                                            class PaymentSagaTest {
                                                private SagaTestFixture<PaymentSaga> fixture;

                                                @BeforeEach
                                                void setUp() {
                                                    fixture = new SagaTestFixture<>(PaymentSaga.class);
                                                }
                                            }
                                            class PaymentSaga {
                                            }
                                            """,
                                            """
                                            package com.example;

                                            import org.axonframework.test.saga.SagaTestFixture;
                                            import org.junit.jupiter.api.AfterEach;
                                            import org.junit.jupiter.api.BeforeEach;

                                            class PaymentSagaTest {
                                                private SagaTestFixture<PaymentSaga> fixture;

                                                @BeforeEach
                                                void setUp() {
                                                    fixture = new SagaTestFixture<>(PaymentSaga.class);
                                                }

                                                @AfterEach
                                                void tearDown() {
                                                    fixture.stop();
                                                }
                                            }
                                            class PaymentSaga {
                                            }
                                            """
                                    )
                            )
                    )
            );
        }
    }

    @Nested
    class TopLevelRecipes {

        private static final String SAGA_POM = """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>rental</artifactId>
                    <version>1.0.0</version>
                </project>
                """;

        private static final String SAGA_SOURCE = """
                package com.example;

                import org.axonframework.modelling.saga.SagaEventHandler;
                import org.axonframework.modelling.saga.SagaLifecycle;

                class PaymentSaga {
                    @SagaEventHandler(associationProperty = "rentalId")
                    void on(Object event) {
                        SagaLifecycle.end();
                    }
                }
                """;

        @Test
        void freeUpgradeLeavesSagasAndAxoniqLegacyOut() {
            // Sagas only run on the commercial axoniq-legacy module, so the free upgrade must not pull it in.
            rewriteRun(
                    spec -> spec.recipe(topLevelRecipe("org.axonframework.migration.UpgradeAxon4ToAxon5")),
                    mavenProject(
                            "rental",
                            pomXml(SAGA_POM, pom -> pom.after(actual -> {
                                assertThat(actual).doesNotContain("axoniq-legacy");
                                return actual;
                            })),
                            srcMainJava(java(SAGA_SOURCE))
                    )
            );
        }

        @Test
        void commercialUpgradeMigratesSagasOntoAxoniqLegacy() {
            rewriteRun(
                    spec -> spec.recipe(topLevelRecipe("io.axoniq.framework.migration.UpgradeAxon4ToAxoniq5"))
                                .markerPrinter(PrintOutputCapture.MarkerPrinter.SEARCH_MARKERS_ONLY),
                    mavenProject(
                            "rental",
                            pomXml(SAGA_POM, pom -> pom.after(actual -> {
                                assertThat(actual).contains("<artifactId>axoniq-legacy</artifactId>");
                                return actual;
                            })),
                            srcMainJava(java(SAGA_SOURCE, source -> source.after(actual -> {
                                assertThat(actual).contains("void on(Object event, SagaLifecycle sagaLifecycle)");
                                return actual;
                            })))
                    )
            );
        }

        private static Recipe topLevelRecipe(String name) {
            return Environment.builder()
                              .scanRuntimeClasspath("org.axonframework.migration")
                              .build()
                              .activateRecipes(name);
        }
    }

    private static void ignoreUnpublishedTargetVersionWarning(RecipeSpec spec) {
        // The target may be an unreleased snapshot in CI. Keep asserting the generated coordinate without rendering
        // Maven's download warning as part of the expected POM.
        spec.markerPrinter(PrintOutputCapture.MarkerPrinter.SEARCH_MARKERS_ONLY);
    }

    private static String axoniqVersion() {
        Properties versions = new Properties();
        try (InputStream input = requireNonNull(
                Axon4ToAxoniq5LegacySagaTest.class.getResourceAsStream("/migration-versions.properties")
        )) {
            versions.load(input);
            return versions.getProperty("axoniq.version");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read migration target versions", exception);
        }
    }
}

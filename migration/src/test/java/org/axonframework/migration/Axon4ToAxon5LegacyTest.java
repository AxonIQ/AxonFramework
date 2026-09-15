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
import org.openrewrite.config.Environment;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static java.util.Objects.requireNonNull;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.kotlin.Assertions.kotlin;
import static org.openrewrite.kotlin.Assertions.srcMainKotlin;
import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Verifies migration of Axon Framework 4 Sagas onto the {@code axon-legacy} compatibility module.
 */
class Axon4ToAxon5LegacyTest implements RewriteTest {

    private static final String AXON_VERSION = axonVersion();

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                               .scanRuntimeClasspath("org.axonframework.migration")
                               .build()
                               .activateRecipes("org.axonframework.migration.Axon4ToAxon5Legacy"))
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
    }

    @Nested
    class DependencyMigration {

        @Test
        void addsAxonLegacyWhenSagaSourceIsPresent() {
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
                            """,
                            """
                            <project>
                                <modelVersion>4.0.0</modelVersion>
                                <groupId>com.example</groupId>
                                <artifactId>rental</artifactId>
                                <version>1.0.0</version>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.axonframework</groupId>
                                        <artifactId>axon-legacy</artifactId>
                                        <version>%s</version>
                                    </dependency>
                                </dependencies>
                            </project>
                            """.formatted(AXON_VERSION)
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
        void addsAxonLegacyForKotlinSagaSource() {
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
                                    """,
                                    """
                                    <project>
                                        <modelVersion>4.0.0</modelVersion>
                                        <groupId>com.example</groupId>
                                        <artifactId>rental</artifactId>
                                        <version>1.0.0</version>
                                        <dependencies>
                                            <dependency>
                                                <groupId>org.axonframework</groupId>
                                                <artifactId>axon-legacy</artifactId>
                                                <version>%s</version>
                                            </dependency>
                                        </dependencies>
                                    </project>
                                    """.formatted(AXON_VERSION)
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
        void doesNotAddAxonLegacyWithoutSagaSource() {
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

    private static String axonVersion() {
        Properties versions = new Properties();
        try (InputStream input = requireNonNull(
                Axon4ToAxon5LegacyTest.class.getResourceAsStream("/migration-versions.properties")
        )) {
            versions.load(input);
            return versions.getProperty("axon.version");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read migration target versions", exception);
        }
    }
}

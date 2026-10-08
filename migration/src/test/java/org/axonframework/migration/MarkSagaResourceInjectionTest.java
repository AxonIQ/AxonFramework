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
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.kotlin.Assertions.kotlin;

/**
 * Verifies {@link MarkSagaResourceInjection}: injected Saga fields and Axon Framework 4 {@code ResourceInjector} usage
 * get a {@code TODO(axon4to5)} marker, since Axon Framework 5 resolves Saga collaborators as handler parameters.
 */
class MarkSagaResourceInjectionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        // The Axon Framework 4 Saga types are not on the test classpath, so the recipe falls back to simple names.
        spec.recipe(new MarkSagaResourceInjection())
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class InjectedSagaFields {

        @Test
        void marksAutowiredFieldOfSaga() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            class PaymentSaga {
                                private String rentalId;
                                @Autowired
                                private transient PaymentService paymentService;

                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object event) {
                                    paymentService.pay(rentalId);
                                }
                            }
                            interface PaymentService { void pay(String rentalId); }
                            """,
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            class PaymentSaga {
                                private String rentalId;
                                // TODO(axon4to5): add this dependency as a parameter of each @SagaEventHandler method that uses it, then remove the field. Axon Framework 5 does not inject Saga fields.
                                @Autowired
                                private transient PaymentService paymentService;

                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object event) {
                                    paymentService.pay(rentalId);
                                }
                            }
                            interface PaymentService { void pay(String rentalId); }
                            """
                    )
            );
        }

        @Test
        void marksInjectFieldOfSpringSagaStereotype() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import jakarta.inject.Inject;
                            import org.axonframework.spring.stereotype.Saga;

                            @Saga
                            class PaymentSaga {
                                /** Charges the customer. */
                                @Inject
                                transient Object paymentService;
                            }
                            """,
                            """
                            package com.example;

                            import jakarta.inject.Inject;
                            import org.axonframework.spring.stereotype.Saga;

                            @Saga
                            class PaymentSaga {
                                /** Charges the customer. */
                                // TODO(axon4to5): add this dependency as a parameter of each @SagaEventHandler method that uses it, then remove the field. Axon Framework 5 does not inject Saga fields.
                                @Inject
                                transient Object paymentService;
                            }
                            """
                    )
            );
        }

        @Test
        void marksInjectedKotlinSagaProperty() {
            rewriteRun(
                    kotlin(
                            """
                            package com.example

                            import org.axonframework.modelling.saga.SagaEventHandler
                            import org.springframework.beans.factory.annotation.Autowired

                            class PaymentSaga {
                                @Autowired
                                private lateinit var paymentService: Any

                                @SagaEventHandler(associationProperty = "rentalId")
                                fun on(event: Any) {
                                }
                            }
                            """,
                            """
                            package com.example

                            import org.axonframework.modelling.saga.SagaEventHandler
                            import org.springframework.beans.factory.annotation.Autowired

                            class PaymentSaga {
                                // TODO(axon4to5): add this dependency as a parameter of each @SagaEventHandler method that uses it, then remove the field. Axon Framework 5 does not inject Saga fields.
                                @Autowired
                                private lateinit var paymentService: Any

                                @SagaEventHandler(associationProperty = "rentalId")
                                fun on(event: Any) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesInjectedFieldsOutsideSagasAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.eventhandling.EventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            class RentalProjection {
                                @Autowired
                                private Object repository;

                                @EventHandler
                                void on(Object event) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesSagaFieldsWithoutInjectionAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;

                            class PaymentSaga {
                                private String rentalId;

                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object event) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesAlreadyMarkedFieldAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            class PaymentSaga {
                                // TODO(axon4to5): add this dependency as a parameter of each @SagaEventHandler method that uses it, then remove the field. Axon Framework 5 does not inject Saga fields.
                                @Autowired
                                private transient Object paymentService;

                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object event) {
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class ResourceInjectorUsage {

        @Test
        void marksSpringResourceInjectorBean() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.ResourceInjector;
                            import org.axonframework.spring.saga.SpringResourceInjector;
                            import org.springframework.context.annotation.Bean;

                            class SagaConfig {
                                @Bean
                                ResourceInjector resourceInjector() {
                                    return new SpringResourceInjector();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.ResourceInjector;
                            import org.axonframework.spring.saga.SpringResourceInjector;
                            import org.springframework.context.annotation.Bean;

                            class SagaConfig {
                                // TODO(axon4to5): ResourceInjector is not ported. Remove it; Saga collaborators are resolved as @SagaEventHandler parameters.
                                @Bean
                                ResourceInjector resourceInjector() {
                                    return new SpringResourceInjector();
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksMemberCallingConfigureResourceInjector() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            class SagaConfig {
                                void configure(Configurer configurer) {
                                    configurer.configureResourceInjector(config -> null);
                                }
                            }
                            interface Configurer { void configureResourceInjector(java.util.function.Function<Object, Object> builder); }
                            """,
                            """
                            package com.example;

                            class SagaConfig {
                                // TODO(axon4to5): ResourceInjector is not ported. Remove it; Saga collaborators are resolved as @SagaEventHandler parameters.
                                void configure(Configurer configurer) {
                                    configurer.configureResourceInjector(config -> null);
                                }
                            }
                            interface Configurer { void configureResourceInjector(java.util.function.Function<Object, Object> builder); }
                            """
                    )
            );
        }

        @Test
        void marksCustomResourceInjectorImplementation() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.ResourceInjector;

                            public class CustomResourceInjector implements ResourceInjector {
                                @Override
                                public void injectResources(Object saga) {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.ResourceInjector;

                            // TODO(axon4to5): ResourceInjector is not ported. Remove it; Saga collaborators are resolved as @SagaEventHandler parameters.
                            public class CustomResourceInjector implements ResourceInjector {
                                @Override
                                public void injectResources(Object saga) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesUnrelatedConfigurationAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.springframework.context.annotation.Bean;

                            class SagaConfig {
                                @Bean
                                Object resourceInjectorLookalike() {
                                    return new Object();
                                }
                            }
                            """
                    )
            );
        }
    }
}

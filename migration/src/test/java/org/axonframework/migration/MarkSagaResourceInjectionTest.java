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
 * Verifies {@link MarkSagaResourceInjection}: injected Saga fields and methods, Spring {@code @Value} Saga fields, and
 * Axon Framework 4 {@code ResourceInjector} usage get a {@code TODO(axon4to5)} marker, since Axon Framework 5 resolves
 * Saga collaborators as handler parameters. After {@link MoveSagaFieldToHandlerParameter} moved the setter-injected
 * fields handlers use, only the setters of the other fields are left to mark.
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
                                // TODO(axon4to5): add this dependency as a parameter of each @SagaEventHandler or @DeadlineHandler method that uses it, then remove the field. Axon Framework 5 does not inject Saga fields. Put a DeadlineMessage parameter first on a @DeadlineHandler without parameters, or the dependency becomes its payload.
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
                                // TODO(axon4to5): add this dependency as a parameter of each @SagaEventHandler or @DeadlineHandler method that uses it, then remove the field. Axon Framework 5 does not inject Saga fields. Put a DeadlineMessage parameter first on a @DeadlineHandler without parameters, or the dependency becomes its payload.
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
                                // TODO(axon4to5): add this dependency as a parameter of each @SagaEventHandler or @DeadlineHandler method that uses it, then remove the field. Axon Framework 5 does not inject Saga fields. Put a DeadlineMessage parameter first on a @DeadlineHandler without parameters, or the dependency becomes its payload.
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
                                // TODO(axon4to5): add this dependency as a parameter of each @SagaEventHandler or @DeadlineHandler method that uses it, then remove the field. Axon Framework 5 does not inject Saga fields. Put a DeadlineMessage parameter first on a @DeadlineHandler without parameters, or the dependency becomes its payload.
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
    class InjectedSagaMethods {

        @Test
        void marksAutowiredSetterOfSaga() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.spring.stereotype.Saga;
                            import org.springframework.beans.factory.annotation.Autowired;

                            @Saga
                            class OrderSaga {
                                private transient Object queryGateway;

                                @Autowired
                                public void setQueryGateway(Object queryGateway) {
                                    this.queryGateway = queryGateway;
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.spring.stereotype.Saga;
                            import org.springframework.beans.factory.annotation.Autowired;

                            @Saga
                            class OrderSaga {
                                private transient Object queryGateway;

                                // TODO(axon4to5): add the dependency this method injects as a parameter of each @SagaEventHandler or @DeadlineHandler method that uses it, then remove this method and the field it sets. Axon Framework 5 does not call injection methods of a Saga. Put a DeadlineMessage parameter first on a @DeadlineHandler without parameters, or the dependency becomes its payload.
                                @Autowired
                                public void setQueryGateway(Object queryGateway) {
                                    this.queryGateway = queryGateway;
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksInjectMethodThatDoesMoreThanAssignAField() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import javax.inject.Inject;

                            import org.axonframework.modelling.saga.SagaEventHandler;

                            class PaymentSaga {
                                private transient Object paymentService;
                                private transient String currency;

                                @Inject
                                void init(Object paymentService) {
                                    this.paymentService = paymentService;
                                    this.currency = paymentService.toString();
                                }

                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object event) {
                                }
                            }
                            """,
                            """
                            package com.example;

                            import javax.inject.Inject;

                            import org.axonframework.modelling.saga.SagaEventHandler;

                            class PaymentSaga {
                                private transient Object paymentService;
                                private transient String currency;

                                // TODO(axon4to5): add the dependency this method injects as a parameter of each @SagaEventHandler or @DeadlineHandler method that uses it, then remove this method and the field it sets. Axon Framework 5 does not call injection methods of a Saga. Put a DeadlineMessage parameter first on a @DeadlineHandler without parameters, or the dependency becomes its payload.
                                @Inject
                                void init(Object paymentService) {
                                    this.paymentService = paymentService;
                                    this.currency = paymentService.toString();
                                }

                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(Object event) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesInjectedSetterOutsideSagasAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.eventhandling.EventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            class RentalProjection {
                                private Object repository;

                                @Autowired
                                void setRepository(Object repository) {
                                    this.repository = repository;
                                }

                                @EventHandler
                                void on(Object event) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesAlreadyMarkedSetterAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.spring.stereotype.Saga;
                            import org.springframework.beans.factory.annotation.Autowired;

                            @Saga
                            class OrderSaga {
                                private transient Object queryGateway;

                                // TODO(axon4to5): add the dependency this method injects as a parameter of each @SagaEventHandler or @DeadlineHandler method that uses it, then remove this method and the field it sets. Axon Framework 5 does not call injection methods of a Saga. Put a DeadlineMessage parameter first on a @DeadlineHandler without parameters, or the dependency becomes its payload.
                                @Autowired
                                public void setQueryGateway(Object queryGateway) {
                                    this.queryGateway = queryGateway;
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class AfterMovingSetterInjectedFields implements RewriteTest {

        @Override
        public void defaults(RecipeSpec spec) {
            // The order of the Saga recipe: setter-injected fields a handler uses move first.
            spec.recipes(new MoveSagaFieldToHandlerParameter(null), new MarkSagaResourceInjection())
                .typeValidationOptions(TypeValidation.none());
        }

        @Test
        void marksOnlyTheSetterOfTheFieldNoHandlerUses() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.modelling.saga.SagaEventHandler;
                            import org.springframework.beans.factory.annotation.Autowired;

                            class PaymentSaga {
                                private transient PaymentService paymentService;
                                private transient Object queryGateway;

                                @Autowired
                                public void setPaymentService(PaymentService paymentService) {
                                    this.paymentService = paymentService;
                                }

                                @Autowired
                                public void setQueryGateway(Object queryGateway) {
                                    this.queryGateway = queryGateway;
                                }

                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(String rentalId) {
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
                                private transient Object queryGateway;

                                // TODO(axon4to5): add the dependency this method injects as a parameter of each @SagaEventHandler or @DeadlineHandler method that uses it, then remove this method and the field it sets. Axon Framework 5 does not call injection methods of a Saga. Put a DeadlineMessage parameter first on a @DeadlineHandler without parameters, or the dependency becomes its payload.
                                @Autowired
                                public void setQueryGateway(Object queryGateway) {
                                    this.queryGateway = queryGateway;
                                }

                                @SagaEventHandler(associationProperty = "rentalId")
                                void on(String rentalId, PaymentService paymentService) {
                                    paymentService.pay(rentalId);
                                }
                            }
                            interface PaymentService { void pay(String rentalId); }
                            """
                    )
            );
        }
    }

    @Nested
    class ValueSagaFields {

        @Test
        void marksValueFieldOfSaga() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.spring.stereotype.Saga;
                            import org.springframework.beans.factory.annotation.Value;

                            @Saga
                            class OrderSaga {
                                @Value("${app.testing.deadline:false}")
                                private boolean isDeadlineTesting;
                                private String scheduleId;
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.spring.stereotype.Saga;
                            import org.springframework.beans.factory.annotation.Value;

                            @Saga
                            class OrderSaga {
                                // TODO(axon4to5): read this property in each @SagaEventHandler or @DeadlineHandler method that uses it, from a Spring Environment parameter or the configuration. Axon Framework 5 does not inject @Value Saga fields, so the field no longer follows the property.
                                @Value("${app.testing.deadline:false}")
                                private boolean isDeadlineTesting;
                                private String scheduleId;
                            }
                            """
                    )
            );
        }

        @Test
        void leavesValueFieldOutsideSagasAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.eventhandling.EventHandler;
                            import org.springframework.beans.factory.annotation.Value;

                            class RentalProjection {
                                @Value("${app.rental.limit:10}")
                                private int limit;

                                @EventHandler
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
                                // TODO(axon4to5): ResourceInjector is not ported. Remove it; Saga collaborators are resolved as handler method parameters.
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
                                // TODO(axon4to5): ResourceInjector is not ported. Remove it; Saga collaborators are resolved as handler method parameters.
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

                            // TODO(axon4to5): ResourceInjector is not ported. Remove it; Saga collaborators are resolved as handler method parameters.
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

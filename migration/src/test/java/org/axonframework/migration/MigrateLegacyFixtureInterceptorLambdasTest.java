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

/**
 * Verifies {@link MigrateLegacyFixtureInterceptorLambdas}.
 */
class MigrateLegacyFixtureInterceptorLambdasTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateLegacyFixtureInterceptorLambdas())
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class SagaTestFixtureInterceptors {

        @Test
        void rewritesABatchDispatchInterceptorToTheSingleMessageShape() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.saga.SagaTestFixture;

                            import static org.axonframework.deadline.GenericDeadlineMessage.asDeadlineMessage;

                            class PaymentSagaTest {
                                SagaTestFixture<Object> fixture;

                                void interceptsDispatch() {
                                    fixture.registerDeadlineDispatchInterceptor(
                                            messages -> (i, m) -> asDeadlineMessage(m.getDeadlineName(), "fake", m.getTimestamp())
                                    )
                                           .givenAggregate("id");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.test.saga.SagaTestFixture;

                            import static org.axonframework.deadline.GenericDeadlineMessage.asDeadlineMessage;

                            class PaymentSagaTest {
                                SagaTestFixture<Object> fixture;

                                void interceptsDispatch() {
                                    fixture.registerDeadlineDispatchInterceptor(
                                            (message, context, chain) -> chain.proceed(asDeadlineMessage(message.getDeadlineName(), "fake", message.getTimestamp()), context)
                                    )
                                           .givenAggregate("id");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void rewritesAUnitOfWorkTransformationToProceedingWithTheChangedMessage() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.saga.SagaTestFixture;

                            import static org.axonframework.deadline.GenericDeadlineMessage.asDeadlineMessage;

                            class PaymentSagaTest {
                                SagaTestFixture<Object> fixture;

                                void interceptsHandling() {
                                    fixture.registerDeadlineHandlerInterceptor((uow, chain) -> {
                                        uow.transformMessage(deadlineMessage -> asDeadlineMessage(
                                                deadlineMessage.getDeadlineName(), "fake", deadlineMessage.getTimestamp())
                                        );
                                        return chain.proceed();
                                    })
                                           .givenAggregate("id");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.test.saga.SagaTestFixture;

                            import static org.axonframework.deadline.GenericDeadlineMessage.asDeadlineMessage;

                            class PaymentSagaTest {
                                SagaTestFixture<Object> fixture;

                                void interceptsHandling() {
                                    fixture.registerDeadlineHandlerInterceptor((message, context, chain) -> chain.proceed(asDeadlineMessage(
                                                message.getDeadlineName(), "fake", message.getTimestamp()), context))
                                           .givenAggregate("id");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void rewritesAnEventHandlerInterceptorReachedThroughTheFixtureConfiguration() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.saga.FixtureConfiguration;

                            class PaymentSagaTest {
                                FixtureConfiguration fixture;

                                void interceptsEvents() {
                                    fixture.registerEventHandlerInterceptor((unitOfWork, interceptorChain) -> interceptorChain.proceed());
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.test.saga.FixtureConfiguration;

                            class PaymentSagaTest {
                                FixtureConfiguration fixture;

                                void interceptsEvents() {
                                    fixture.registerEventHandlerInterceptor((message, context, chain) -> chain.proceed(message, context));
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void avoidsLambdaParameterNamesAlreadyDeclaredInTheTestMethod() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.saga.SagaTestFixture;

                            class PaymentSagaTest {
                                SagaTestFixture<Object> fixture;

                                void interceptsHandling() {
                                    String context = "test";
                                    fixture.registerDeadlineHandlerInterceptor((uow, chain) -> chain.proceed());
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.test.saga.SagaTestFixture;

                            class PaymentSagaTest {
                                SagaTestFixture<Object> fixture;

                                void interceptsHandling() {
                                    String context = "test";
                                    fixture.registerDeadlineHandlerInterceptor((message, context1, chain) -> chain.proceed(message, context1));
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class StubDeadlineManagerInterceptors {

        @Test
        void rewritesDispatchAndHandlerInterceptors() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.messaging.MetaData;
                            import org.axonframework.test.deadline.StubDeadlineManager;

                            class DeadlineTest {
                                void intercepts() {
                                    StubDeadlineManager manager = new StubDeadlineManager();
                                    manager.registerHandlerInterceptor((uow, chain) -> {
                                        return chain.proceed();
                                    });
                                    manager.registerDispatchInterceptor(messages -> (i, m) -> {
                                        return m.andMetaData(MetaData.with("key", "value"));
                                    });
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.messaging.MetaData;
                            import org.axonframework.test.deadline.StubDeadlineManager;

                            class DeadlineTest {
                                void intercepts() {
                                    StubDeadlineManager manager = new StubDeadlineManager();
                                    manager.registerHandlerInterceptor((message, context, chain) -> chain.proceed(message, context));
                                    manager.registerDispatchInterceptor((message, context, chain) -> chain.proceed(message.andMetaData(MetaData.with("key", "value")), context));
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class InterceptorsNeedingManualWork {

        @Test
        void marksADispatchInterceptorThatUsesTheMessageIndex() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.test.saga.SagaTestFixture;

                            class PaymentSagaTest {
                                SagaTestFixture<Object> fixture;

                                void interceptsDispatch() {
                                    fixture.registerDeadlineDispatchInterceptor(messages -> (i, m) -> i == 0 ? m : null)
                                           .givenAggregate("id");
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.test.saga.SagaTestFixture;

                            class PaymentSagaTest {
                                SagaTestFixture<Object> fixture;

                                void interceptsDispatch() {
                                    // TODO(axon4to5): rewrite this dispatch interceptor as (message, context, chain) -> chain.proceed(message, context). Axon Framework 5 interceptors receive one message and hand it on through the chain, instead of returning a function over a message batch.
                                    fixture.registerDeadlineDispatchInterceptor(messages -> (i, m) -> i == 0 ? m : null)
                                           .givenAggregate("id");
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void marksMethodReferencesAnonymousClassesAndOtherHandlerBodiesOnTheirStatements() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.messaging.InterceptorChain;
                            import org.axonframework.messaging.MessageHandlerInterceptor;
                            import org.axonframework.messaging.unitofwork.UnitOfWork;
                            import org.axonframework.test.deadline.StubDeadlineManager;

                            class DeadlineTest {
                                void intercepts(StubDeadlineManager manager) {
                                    manager.registerHandlerInterceptor(this::intercept);
                                    manager.registerHandlerInterceptor(new MessageHandlerInterceptor<DeadlineMessage<?>>() {
                                        @Override
                                        public Object handle(UnitOfWork<? extends DeadlineMessage<?>> unitOfWork,
                                                             InterceptorChain chain) throws Exception {
                                            return chain.proceed();
                                        }
                                    });
                                    manager.registerHandlerInterceptor((uow, chain) -> {
                                        uow.onCommit(u -> System.out.println("committed"));
                                        return chain.proceed();
                                    });
                                }

                                Object intercept(UnitOfWork<? extends DeadlineMessage<?>> unitOfWork, InterceptorChain chain)
                                        throws Exception {
                                    return chain.proceed();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.messaging.InterceptorChain;
                            import org.axonframework.messaging.MessageHandlerInterceptor;
                            import org.axonframework.messaging.unitofwork.UnitOfWork;
                            import org.axonframework.test.deadline.StubDeadlineManager;

                            class DeadlineTest {
                                void intercepts(StubDeadlineManager manager) {
                                    // TODO(axon4to5): rewrite this handler interceptor as (message, context, chain) -> chain.proceed(message, context). Axon Framework 5 has no unit of work to transform the message on; pass the changed message to chain.proceed(..) instead.
                                    manager.registerHandlerInterceptor(this::intercept);
                                    // TODO(axon4to5): rewrite this handler interceptor as (message, context, chain) -> chain.proceed(message, context). Axon Framework 5 has no unit of work to transform the message on; pass the changed message to chain.proceed(..) instead.
                                    manager.registerHandlerInterceptor(new MessageHandlerInterceptor<DeadlineMessage<?>>() {
                                        @Override
                                        public Object handle(UnitOfWork<? extends DeadlineMessage<?>> unitOfWork,
                                                             InterceptorChain chain) throws Exception {
                                            return chain.proceed();
                                        }
                                    });
                                    // TODO(axon4to5): rewrite this handler interceptor as (message, context, chain) -> chain.proceed(message, context). Axon Framework 5 has no unit of work to transform the message on; pass the changed message to chain.proceed(..) instead.
                                    manager.registerHandlerInterceptor((uow, chain) -> {
                                        uow.onCommit(u -> System.out.println("committed"));
                                        return chain.proceed();
                                    });
                                }

                                Object intercept(UnitOfWork<? extends DeadlineMessage<?>> unitOfWork, InterceptorChain chain)
                                        throws Exception {
                                    return chain.proceed();
                                }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class InterceptorsLeftAlone {

        @Test
        void leavesInterceptorsAlreadyInTheAxonFramework5ShapeAndNamedInstances() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineMessage;
                            import org.axonframework.messaging.MessageDispatchInterceptor;
                            import org.axonframework.test.saga.SagaTestFixture;

                            class PaymentSagaTest {
                                SagaTestFixture<Object> fixture;
                                MessageDispatchInterceptor<DeadlineMessage<?>> interceptor;

                                void intercepts() {
                                    fixture.registerDeadlineDispatchInterceptor(interceptor);
                                    fixture.registerDeadlineHandlerInterceptor((message, context, chain) -> chain.proceed(message, context));
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesInterceptorLambdasOnOtherComponentsAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.commandhandling.SimpleCommandBus;

                            class Configuration {
                                void intercepts(SimpleCommandBus commandBus) {
                                    commandBus.registerHandlerInterceptor((uow, chain) -> chain.proceed());
                                    commandBus.registerDispatchInterceptor(messages -> (i, m) -> m);
                                }
                            }
                            """
                    )
            );
        }
    }
}

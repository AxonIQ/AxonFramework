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
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies {@link MigrateMessageInterceptorLambda}'s rewrite of inline
 * {@code MessageHandlerInterceptor<…> = (uow, chain) -> …} lambdas to the AF5
 * three-parameter shape, including the {@code chain.proceed()} call inside the body.
 */
class MigrateMessageInterceptorLambdaTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateMessageInterceptorLambda())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void rewritesLambdaParametersAndProceedCall() {
        rewriteRun(
                java(
                        // AF5 type stubs for the lambda's target type — the recipe matches on the
                        // FQN, so a minimal placeholder is enough.
                        """
                        package org.axonframework.messaging.core;
                        public interface MessageHandlerInterceptor<M> {
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.core.MessageHandlerInterceptor;
                        class Wiring {
                            void register() {
                                MessageHandlerInterceptor<Object> interceptor = (unitOfWork, interceptorChain) -> {
                                    return interceptorChain.proceed();
                                };
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.messaging.core.MessageHandlerInterceptor;
                        class Wiring {
                            void register() {
                                MessageHandlerInterceptor<Object> interceptor = /* TODO(axon4to5): review lambda body — the dropped `unitOfWork` parameter has no AF5 equivalent; references to it must be replaced with calls on `message` / `processingContext`.*/ (message, processingContext, interceptorChain) -> {
                                    return interceptorChain.proceed(message, processingContext);
                                };
                            }
                        }
                        """
                )
        );
    }

    @Test
    void preservesUserChosenChainParameterName() {
        // The recipe must preserve whatever name the developer chose for the chain
        // parameter (not unconditionally rename it). Here the second parameter is `chain`,
        // and the body uses `chain.proceed()`.
        rewriteRun(
                java(
                        """
                        package org.axonframework.messaging.core;
                        public interface MessageHandlerInterceptor<M> {
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.core.MessageHandlerInterceptor;
                        class Wiring {
                            void register() {
                                MessageHandlerInterceptor<Object> interceptor = (uow, chain) -> chain.proceed();
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.messaging.core.MessageHandlerInterceptor;
                        class Wiring {
                            void register() {
                                MessageHandlerInterceptor<Object> interceptor = /* TODO(axon4to5): review lambda body — the dropped `unitOfWork` parameter has no AF5 equivalent; references to it must be replaced with calls on `message` / `processingContext`.*/ (message, processingContext, chain) ->
                                chain.proceed(message, processingContext);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void isIdempotentWhenLambdaAlreadyOnAf5Shape() {
        // A three-parameter lambda is already on the AF5 shape — the recipe skips it.
        rewriteRun(
                java(
                        """
                        package org.axonframework.messaging.core;
                        public interface MessageHandlerInterceptor<M> {
                        }
                        """
                ),
                java(
                        """
                        package com.example;
                        import org.axonframework.messaging.core.MessageHandlerInterceptor;
                        class Wiring {
                            void register() {
                                MessageHandlerInterceptor<Object> interceptor = (message, processingContext, chain) -> chain.proceed(message, processingContext);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesUnrelatedLambdaTypesAlone() {
        // The recipe is scoped to `MessageHandlerInterceptor` variable declarations.
        // Lambdas bound to other functional interfaces are passed through untouched.
        rewriteRun(
                java(
                        """
                        package com.example;
                        import java.util.function.BiFunction;
                        class Wiring {
                            void register() {
                                BiFunction<Object, Object, Object> f = (a, b) -> a;
                            }
                        }
                        """
                )
        );
    }
}

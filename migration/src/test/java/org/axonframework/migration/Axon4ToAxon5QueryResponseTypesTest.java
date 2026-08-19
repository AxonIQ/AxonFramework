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
 * Verifies the narrow {@code ResponseTypes} unwrapping behaviour of
 * {@link Axon4ToAxon5QueryResponseTypes}: only fires on two-argument typed-payload
 * {@code query(...)} calls, leaves three-argument string-named forms / {@code subscriptionQuery}
 * / {@code streamingQuery} untouched, and only removes {@code ResponseType*} imports when
 * no references remain.
 */
class Axon4ToAxon5QueryResponseTypesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new Axon4ToAxon5QueryResponseTypes());
    }

    // ── Positive cases ───────────────────────────────────────────────────────

    @Test
    void unwrapsInstanceOfOnTwoArgQuery() {
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.queryhandling.QueryGateway;
                        import org.axonframework.messaging.responsetypes.ResponseTypes;
                        import java.util.concurrent.CompletableFuture;
                        class Foo {
                            private final QueryGateway gateway;
                            Foo(QueryGateway gateway) { this.gateway = gateway; }
                            CompletableFuture<String> findOne(Object payload) {
                                return gateway.query(payload, ResponseTypes.instanceOf(String.class));
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.queryhandling.QueryGateway;
                        import java.util.concurrent.CompletableFuture;
                        class Foo {
                            private final QueryGateway gateway;
                            Foo(QueryGateway gateway) { this.gateway = gateway; }
                            CompletableFuture<String> findOne(Object payload) {
                                return gateway.query(payload, String.class);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void unwrapsOptionalInstanceOfOnTwoArgQuery() {
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.queryhandling.QueryGateway;
                        import org.axonframework.messaging.responsetypes.ResponseTypes;
                        import java.util.concurrent.CompletableFuture;
                        class Foo {
                            private final QueryGateway gateway;
                            Foo(QueryGateway gateway) { this.gateway = gateway; }
                            CompletableFuture<String> findOne(Object payload) {
                                return gateway.query(payload, ResponseTypes.optionalInstanceOf(String.class));
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.queryhandling.QueryGateway;
                        import java.util.concurrent.CompletableFuture;
                        class Foo {
                            private final QueryGateway gateway;
                            Foo(QueryGateway gateway) { this.gateway = gateway; }
                            CompletableFuture<String> findOne(Object payload) {
                                return gateway.query(payload, String.class);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void renamesQueryToQueryManyOnMultipleInstancesOf() {
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.queryhandling.QueryGateway;
                        import org.axonframework.messaging.responsetypes.ResponseTypes;
                        import java.util.List;
                        import java.util.concurrent.CompletableFuture;
                        class Foo {
                            private final QueryGateway gateway;
                            Foo(QueryGateway gateway) { this.gateway = gateway; }
                            CompletableFuture<List<String>> findAll(Object payload) {
                                return gateway.query(payload, ResponseTypes.multipleInstancesOf(String.class));
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.queryhandling.QueryGateway;
                        import java.util.List;
                        import java.util.concurrent.CompletableFuture;
                        class Foo {
                            private final QueryGateway gateway;
                            Foo(QueryGateway gateway) { this.gateway = gateway; }
                            CompletableFuture<List<String>> findAll(Object payload) {
                                return gateway.queryMany(payload, String.class);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void unwrapsStaticImportedInstanceOf() {
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.queryhandling.QueryGateway;
                        import static org.axonframework.messaging.responsetypes.ResponseTypes.instanceOf;
                        import java.util.concurrent.CompletableFuture;
                        class Foo {
                            private final QueryGateway gateway;
                            Foo(QueryGateway gateway) { this.gateway = gateway; }
                            CompletableFuture<String> findOne(Object payload) {
                                return gateway.query(payload, instanceOf(String.class));
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.queryhandling.QueryGateway;
                        import java.util.concurrent.CompletableFuture;
                        class Foo {
                            private final QueryGateway gateway;
                            Foo(QueryGateway gateway) { this.gateway = gateway; }
                            CompletableFuture<String> findOne(Object payload) {
                                return gateway.query(payload, String.class);
                            }
                        }
                        """
                )
        );
    }

    // ── Negative cases — fingerprints the per-construct skill needs ──────────

    @Test
    void leavesThreeArgStringNamedQueryUntouched() {
        // The 3-arg AF4 form has no AF5 overload — rewriting it would (a) break compilation and
        // (b) destroy the fingerprint the LLM-driven skill needs to introduce a typed query class.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.queryhandling.QueryGateway;
                        import org.axonframework.messaging.responsetypes.ResponseTypes;
                        import java.util.List;
                        import java.util.concurrent.CompletableFuture;
                        class Foo {
                            private final QueryGateway gateway;
                            Foo(QueryGateway gateway) { this.gateway = gateway; }
                            CompletableFuture<List<String>> findAll() {
                                return gateway.query("findAll", null, ResponseTypes.multipleInstancesOf(String.class));
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesSubscriptionQueryUntouched() {
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.queryhandling.QueryGateway;
                        import org.axonframework.queryhandling.SubscriptionQueryResult;
                        import org.axonframework.messaging.responsetypes.ResponseTypes;
                        class Foo {
                            private final QueryGateway gateway;
                            Foo(QueryGateway gateway) { this.gateway = gateway; }
                            SubscriptionQueryResult<String, String> watch(Object payload) {
                                return gateway.subscriptionQuery(payload,
                                                                  ResponseTypes.instanceOf(String.class),
                                                                  ResponseTypes.instanceOf(String.class));
                            }
                        }
                        """
                )
        );
    }

    @Test
    void leavesStreamingQueryUntouched() {
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.queryhandling.QueryGateway;
                        import org.reactivestreams.Publisher;
                        class Foo {
                            private final QueryGateway gateway;
                            Foo(QueryGateway gateway) { this.gateway = gateway; }
                            Publisher<String> stream(Object payload) {
                                return gateway.streamingQuery(payload, String.class);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void isIdempotentOnAlreadyAf5Shape() {
        // Already in AF5 shape — the recipe must be a no-op.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.queryhandling.QueryGateway;
                        import java.util.concurrent.CompletableFuture;
                        class Foo {
                            private final QueryGateway gateway;
                            Foo(QueryGateway gateway) { this.gateway = gateway; }
                            CompletableFuture<String> findOne(Object payload) {
                                return gateway.query(payload, String.class);
                            }
                        }
                        """
                )
        );
    }

    @Test
    void preservesResponseTypesImportWhenSomeCallsRemain() {
        // Mixed file: one 2-arg call gets unwrapped, one 3-arg call stays. The
        // ResponseTypes import must remain because the 3-arg site still references it.
        rewriteRun(
                spec -> spec.typeValidationOptions(TypeValidation.none()),
                java(
                        """
                        package com.example;
                        import org.axonframework.queryhandling.QueryGateway;
                        import org.axonframework.messaging.responsetypes.ResponseTypes;
                        import java.util.List;
                        import java.util.concurrent.CompletableFuture;
                        class Foo {
                            private final QueryGateway gateway;
                            Foo(QueryGateway gateway) { this.gateway = gateway; }
                            CompletableFuture<String> findOne(Object payload) {
                                return gateway.query(payload, ResponseTypes.instanceOf(String.class));
                            }
                            CompletableFuture<List<String>> findAll() {
                                return gateway.query("findAll", null, ResponseTypes.multipleInstancesOf(String.class));
                            }
                        }
                        """,
                        """
                        package com.example;
                        import org.axonframework.queryhandling.QueryGateway;
                        import org.axonframework.messaging.responsetypes.ResponseTypes;
                        import java.util.List;
                        import java.util.concurrent.CompletableFuture;
                        class Foo {
                            private final QueryGateway gateway;
                            Foo(QueryGateway gateway) { this.gateway = gateway; }
                            CompletableFuture<String> findOne(Object payload) {
                                return gateway.query(payload, String.class);
                            }
                            CompletableFuture<List<String>> findAll() {
                                return gateway.query("findAll", null, ResponseTypes.multipleInstancesOf(String.class));
                            }
                        }
                        """
                )
        );
    }
}

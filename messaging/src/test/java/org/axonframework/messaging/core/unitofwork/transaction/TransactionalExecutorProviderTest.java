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

package org.axonframework.messaging.core.unitofwork.transaction;

import org.axonframework.common.function.ThrowingFunction;
import org.axonframework.common.tx.MaterializationAware;
import org.axonframework.common.tx.TransactionalExecutor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating {@link TransactionalExecutorProvider}'s default methods.
 *
 * @author John Hendrikx
 */
class TransactionalExecutorProviderTest {

    private static final ProcessingContext CONTEXT = new StubProcessingContext();

    @Nested
    class GetTransactionalExecutor {

        @Test
        void shouldReturnTheBoundExecutor() {
            FakeExecutor ambient = new FakeExecutor("ambient", true);
            TransactionalExecutorProvider<String> provider = new FakeProvider(new FakeExecutor("independent", null), ambient);

            assertThat(provider.getTransactionalExecutor(CONTEXT)).isSameAs(ambient);
        }

        @Test
        void shouldThrowWhenNoExecutorIsBoundToTheContext() {
            TransactionalExecutorProvider<String> provider = new FakeProvider(new FakeExecutor("independent", null), null);

            assertThatThrownBy(() -> provider.getTransactionalExecutor(CONTEXT))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class PreferringIndependent {

        @Test
        void shouldUseIndependentExecutorWhenProcessingContextIsNull() throws Exception {
            FakeExecutor independent = new FakeExecutor("independent", null);
            TransactionalExecutorProvider<String> provider = new FakeProvider(independent, null);

            String result = provider.getTransactionalExecutorPreferringIndependent(null)
                                     .apply(r -> r)
                                     .get();

            assertThat(result).isEqualTo("independent");
        }

        @Test
        void shouldFallBackToIndependentWhenNoExecutorIsBoundToTheContext() throws Exception {
            // given - no transaction manager attached to this unit of work at all
            TransactionalExecutorProvider<String> provider = new FakeProvider(new FakeExecutor("independent", null), null);

            String result = provider.getTransactionalExecutorPreferringIndependent(CONTEXT)
                                     .apply(r -> r)
                                     .get();

            assertThat(result).isEqualTo("independent");
        }

        @Test
        void shouldUseIndependentExecutorWhenAmbientIsNotYetActive() throws Exception {
            FakeExecutor independent = new FakeExecutor("independent", null);
            FakeExecutor ambient = new FakeExecutor("ambient", false);
            TransactionalExecutorProvider<String> provider = new FakeProvider(independent, ambient);

            String result = provider.getTransactionalExecutorPreferringIndependent(CONTEXT)
                                     .apply(r -> r)
                                     .get();

            assertThat(result).isEqualTo("independent");
        }

        @Test
        void shouldReuseTheAmbientExecutorWhenAlreadyActive() throws Exception {
            FakeExecutor independent = new FakeExecutor("independent", null);
            FakeExecutor ambient = new FakeExecutor("ambient", true);
            TransactionalExecutorProvider<String> provider = new FakeProvider(independent, ambient);

            String result = provider.getTransactionalExecutorPreferringIndependent(CONTEXT)
                                     .apply(r -> r)
                                     .get();

            assertThat(result).isEqualTo("ambient");
        }

        @Test
        void shouldUseTheAmbientExecutorUnconditionallyWhenItIsNotMaterializationAware() throws Exception {
            FakeExecutor independent = new FakeExecutor("independent", null);
            TransactionalExecutor<String> ambient = new TransactionalExecutor<>() {
                @Override
                public <R> CompletableFuture<R> apply(ThrowingFunction<String, R, Exception> function) {
                    try {
                        return CompletableFuture.completedFuture(function.apply("ambient"));
                    }
                    catch (Exception e) {
                        return CompletableFuture.failedFuture(e);
                    }
                }
            };
            TransactionalExecutorProvider<String> provider = new TransactionalExecutorProvider<>() {
                @Override
                public TransactionalExecutor<String> findTransactionalExecutor(@Nullable ProcessingContext processingContext) {
                    return processingContext == null ? independent : ambient;
                }
            };

            String result = provider.getTransactionalExecutorPreferringIndependent(CONTEXT)
                                     .apply(r -> r)
                                     .get();

            assertThat(result).isEqualTo("ambient");
        }
    }

    /**
     * A {@link TransactionalExecutor} that reports the given resource and, if {@code active} is non-null,
     * also implements {@link MaterializationAware} reporting that fixed value.
     */
    private static class FakeExecutor implements TransactionalExecutor<String>, MaterializationAware {
        private final String resource;
        private final Boolean active;

        FakeExecutor(String resource, @Nullable Boolean active) {
            this.resource = resource;
            this.active = active;
        }

        @Override
        public <R> R withMaterializationShielded(Function<Boolean, R> decision) {
            return decision.apply(active != null && active);
        }

        @Override
        public <R> CompletableFuture<R> apply(ThrowingFunction<String, R, Exception> function) {
            try {
                return CompletableFuture.completedFuture(function.apply(resource));
            }
            catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
    }

    private record FakeProvider(FakeExecutor independent, @Nullable FakeExecutor ambient)
            implements TransactionalExecutorProvider<String> {
        @Override
        public @Nullable TransactionalExecutor<String> findTransactionalExecutor(@Nullable ProcessingContext processingContext) {
            return processingContext == null ? independent : ambient;
        }
    }
}

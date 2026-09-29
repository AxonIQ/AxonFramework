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

package org.axonframework.messaging.core;

import org.junit.jupiter.api.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class pinning the Axon Framework 4 behaviour of {@link Scope}, which is ported unchanged: the current scope is
 * a per-thread stack, asking for it while the stack is empty throws, and a scope is only current while the task it
 * executes runs.
 */
class ScopeTest {

    @AfterEach
    void assertNoScopeIsLeftActive() {
        // A scope left behind would leak into whichever test runs next on this thread.
        assertThatThrownBy(Scope::getCurrentScope).isInstanceOf(IllegalStateException.class);
    }

    @Nested
    class WithoutAnActiveScope {

        @Test
        void getCurrentScopeThrows() {
            // when / then
            assertThatThrownBy(Scope::getCurrentScope)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cannot request current Scope if none is active");
        }

        /**
         * Axon Framework 4 threw here too. Only its {@code ScopeDescriptorParameterResolverFactory} caught the
         * exception, which is why a handler parameter falls back to {@link NoScopeDescriptor} while a direct call
         * does not.
         */
        @Test
        void describeCurrentScopeThrows() {
            // when / then
            assertThatThrownBy(Scope::describeCurrentScope)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cannot request current Scope if none is active");
        }
    }

    @Nested
    class ExecuteWithResult {

        @Test
        void makesTheScopeCurrentWhileTheTaskRuns() throws Exception {
            // given
            TestScope scope = new TestScope("scope");

            // when
            ScopeDescriptor described = scope.executeWithResult(Scope::describeCurrentScope);

            // then
            assertThat(described).isSameAs(scope.descriptor);
        }

        @Test
        void returnsTheResultOfTheTask() throws Exception {
            // given
            TestScope scope = new TestScope("scope");

            // when
            String result = scope.executeWithResult(() -> "result");

            // then
            assertThat(result).isEqualTo("result");
        }

        @Test
        void endsTheScopeOnceTheTaskCompletes() throws Exception {
            // given
            TestScope scope = new TestScope("scope");

            // when
            scope.executeWithResult(() -> null);

            // then
            assertThatThrownBy(Scope::getCurrentScope).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void endsTheScopeWhenTheTaskThrows() {
            // given
            TestScope scope = new TestScope("scope");

            // when
            assertThatThrownBy(() -> scope.executeWithResult(() -> {
                throw new IllegalArgumentException("task failure");
            })).isInstanceOf(IllegalArgumentException.class).hasMessage("task failure");

            // then
            assertThatThrownBy(Scope::getCurrentScope).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void aNestedScopeReplacesTheOuterScopeUntilItsTaskCompletes() throws Exception {
            // given
            TestScope outer = new TestScope("outer");
            TestScope inner = new TestScope("inner");
            AtomicReference<ScopeDescriptor> seenByInnerTask = new AtomicReference<>();

            // when
            ScopeDescriptor seenAfterInnerTask = outer.executeWithResult(() -> {
                inner.executeWithResult(() -> {
                    seenByInnerTask.set(Scope.describeCurrentScope());
                    return null;
                });
                return Scope.describeCurrentScope();
            });

            // then
            assertThat(seenByInnerTask.get()).isSameAs(inner.descriptor);
            assertThat(seenAfterInnerTask).isSameAs(outer.descriptor);
        }

        @Test
        void theScopeIsNotVisibleOnAnotherThread() throws Exception {
            // given
            TestScope scope = new TestScope("scope");

            // when
            Throwable failureOnOtherThread = scope.executeWithResult(
                    () -> CompletableFuture.supplyAsync(() -> {
                        try {
                            Scope.getCurrentScope();
                            return null;
                        } catch (IllegalStateException e) {
                            return e;
                        }
                    }).get(5, TimeUnit.SECONDS)
            );

            // then
            assertThat(failureOnOtherThread).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class EndScope {

        @Test
        void endingAScopeThatIsNotTheCurrentOneThrows() {
            // given
            TestScope current = new TestScope("current");
            TestScope other = new TestScope("other");
            current.startScope();

            try {
                // when / then
                assertThatThrownBy(other::endScope)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Incorrectly trying to end another Scope then which the calling process is "
                                            + "contained in.");
            } finally {
                current.endScope();
            }
        }

        /**
         * Ending a scope that was never started throws the same "another Scope" message as ending the wrong one,
         * because the empty stack's top is {@code null} rather than {@code this}. Inherited from Axon Framework 4.
         */
        @Test
        void endingAScopeWhileNoneIsActiveThrowsTheSameMessage() {
            // given
            TestScope neverStarted = new TestScope("neverStarted");

            // when / then
            assertThatThrownBy(neverStarted::endScope)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Incorrectly trying to end another Scope then which the calling process is "
                                        + "contained in.");
        }
    }

    private static final class TestScope extends Scope {

        private final ScopeDescriptor descriptor;

        private TestScope(String description) {
            this.descriptor = () -> description;
        }

        @Override
        public ScopeDescriptor describeScope() {
            return descriptor;
        }
    }
}

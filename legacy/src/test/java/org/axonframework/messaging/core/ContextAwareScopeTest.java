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

import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating {@link ContextAwareScope#currentProcessingContext()}, which is how code without a
 * {@link ProcessingContext} parameter of its own finds the context of the handler invocation it is called from.
 */
class ContextAwareScopeTest {

    @AfterEach
    void assertNoScopeIsLeftActive() {
        // A scope left behind would leak into whichever test runs next on this thread.
        assertThatThrownBy(Scope::getCurrentScope).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void currentProcessingContextIsEmptyWhenNoScopeIsActive() {
        // when
        Optional<ProcessingContext> result = ContextAwareScope.currentProcessingContext();

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void currentProcessingContextIsTheContextOfTheCurrentScope() throws Exception {
        // given
        ProcessingContext context = new StubProcessingContext();
        TestContextAwareScope scope = new TestContextAwareScope(context);

        // when
        Optional<ProcessingContext> result = scope.executeWithResult(ContextAwareScope::currentProcessingContext);

        // then
        assertThat(result).containsSame(context);
    }

    @Test
    void currentProcessingContextIsEmptyWhenTheCurrentScopeCarriesNoContext() throws Exception {
        // given
        Scope plainScope = new Scope() {
            @Override
            public ScopeDescriptor describeScope() {
                return () -> "plain";
            }
        };

        // when
        Optional<ProcessingContext> result = plainScope.executeWithResult(ContextAwareScope::currentProcessingContext);

        // then
        assertThat(result).isEmpty();
    }

    /**
     * The innermost scope wins, as it does for {@link Scope#describeCurrentScope()}: a plain scope started from
     * within a context-aware one hides that context until it ends.
     */
    @Test
    void aNestedScopeWithoutContextHidesTheContextOfTheOuterScope() throws Exception {
        // given
        TestContextAwareScope outer = new TestContextAwareScope(new StubProcessingContext());
        Scope inner = new Scope() {
            @Override
            public ScopeDescriptor describeScope() {
                return () -> "inner";
            }
        };

        // when
        Optional<ProcessingContext> result = outer.executeWithResult(
                () -> inner.executeWithResult(ContextAwareScope::currentProcessingContext)
        );

        // then
        assertThat(result).isEmpty();
    }

    private static final class TestContextAwareScope extends ContextAwareScope {

        private final ProcessingContext context;

        private TestContextAwareScope(ProcessingContext context) {
            this.context = context;
        }

        @Override
        public ProcessingContext processingContext() {
            return context;
        }

        @Override
        public ScopeDescriptor describeScope() {
            return () -> "contextAware";
        }
    }
}

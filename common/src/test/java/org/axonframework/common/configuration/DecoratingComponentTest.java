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

package org.axonframework.common.configuration;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating {@link DecoratingComponent}.
 *
 * @author John Hendrikx
 */
class DecoratingComponentTest {

    private static class Wrapper implements DecoratingComponent {

        private final Object delegate;

        Wrapper(Object delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object decoratedDelegate() {
            return delegate;
        }
    }

    @Test
    void unwrapFullyReturnsAPlainValueUnchanged() {
        Object plain = new Object();

        assertSame(plain, DecoratingComponent.unwrapFully(plain));
    }

    @Test
    void unwrapFullyReturnsNullForNullInput() {
        assertNull(DecoratingComponent.unwrapFully(null));
    }

    @Test
    void unwrapFullyFollowsASingleLevelOfWrapping() {
        Object root = new Object();
        Wrapper wrapper = new Wrapper(root);

        assertSame(root, DecoratingComponent.unwrapFully(wrapper));
    }

    @Test
    void unwrapFullyFollowsAChainOfTwoWrappers() {
        Object root = new Object();
        Wrapper inner = new Wrapper(root);
        Wrapper outer = new Wrapper(inner);

        assertSame(root, DecoratingComponent.unwrapFully(outer));
    }

    @Test
    void instanceUnwrapFullyMatchesTheStaticFormCalledWithThis() {
        Object root = new Object();
        Wrapper inner = new Wrapper(root);
        Wrapper outer = new Wrapper(inner);

        assertSame(DecoratingComponent.unwrapFully(outer), outer.unwrapFully());
    }
}

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating {@link CurrentScope}, in particular that it falls back to {@link NoScopeDescriptor#INSTANCE}
 * rather than throwing when nothing is registered -- the deliberate difference from {@code SagaLifecycle.forContext(...)}.
 */
class CurrentScopeTest {

    @Test
    void describeCurrentScopeReturnsTheRegisteredScopeDescriptor() {
        ScopeDescriptor descriptor = () -> "some scope";
        ProcessingContext context = new StubProcessingContext().withResource(CurrentScope.RESOURCE_KEY, descriptor);

        assertThat(CurrentScope.describeCurrentScope(context)).isSameAs(descriptor);
    }

    @Test
    void describeCurrentScopeFallsBackToNoScopeDescriptorWhenNothingIsRegistered() {
        ProcessingContext context = new StubProcessingContext();

        assertThat(CurrentScope.describeCurrentScope(context)).isSameAs(NoScopeDescriptor.INSTANCE);
    }
}

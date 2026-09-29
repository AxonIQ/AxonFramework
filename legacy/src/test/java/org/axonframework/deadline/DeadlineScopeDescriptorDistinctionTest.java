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

package org.axonframework.deadline;

import org.axonframework.modelling.command.AggregateScopeDescriptor;
import org.axonframework.modelling.saga.SagaScopeDescriptor;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that an {@link AggregateScopeDescriptor} and a {@link SagaScopeDescriptor} stay distinguishable even when
 * their {@code type} and {@code identifier} coincide: a {@link DeadlineManager} stores whichever descriptor a
 * deadline was scheduled against, so on expiry it must be able to tell an aggregate-scoped deadline from a
 * saga-scoped one.
 */
class DeadlineScopeDescriptorDistinctionTest {

    private static final String IDENTIFIER = "target-id";

    @Test
    void aggregateAndSagaScopesNeverMatchEachOtherDespiteEqualTypeAndIdentifier() {
        // given
        AggregateScopeDescriptor aggregateScope = new AggregateScopeDescriptor("Shared", IDENTIFIER);
        SagaScopeDescriptor sagaScope = new SagaScopeDescriptor("Shared", IDENTIFIER);

        // then
        assertThat(aggregateScope).isNotEqualTo(sagaScope);
        assertThat(sagaScope).isNotEqualTo(aggregateScope);
    }

    @Test
    void scopeDescriptionNamesTheFlavorItDescribes() {
        // given
        AggregateScopeDescriptor aggregateScope = new AggregateScopeDescriptor("MyAggregate", IDENTIFIER);
        SagaScopeDescriptor sagaScope = new SagaScopeDescriptor("MySaga", IDENTIFIER);

        // then
        assertThat(aggregateScope.scopeDescription())
                .isEqualTo("AggregateScopeDescriptor for type [MyAggregate] and identifier [target-id]");
        assertThat(sagaScope.scopeDescription())
                .isEqualTo("SagaScopeDescriptor for type [MySaga] and identifier [target-id]");
    }
}

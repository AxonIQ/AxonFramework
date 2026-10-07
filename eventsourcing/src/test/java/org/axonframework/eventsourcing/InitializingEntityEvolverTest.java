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

package org.axonframework.eventsourcing;

import org.axonframework.eventsourcing.handler.InitializingEntityEvolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.repository.EntityNotFoundException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.messaging.eventhandling.EventTestUtils.createEvent;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test for {@link InitializingEntityEvolver}.
 *
 * @author John Hendrikx
 */
class InitializingEntityEvolverTest {

    private final EventSourcedEntityFactory<Integer, String> entityFactory = mock();
    private final EntityEvolver<String> entityEvolver = mock();
    private final InitializingEntityEvolver<Integer, String> evolver = new InitializingEntityEvolver<>(entityFactory, entityEvolver);

    private final ProcessingContext context = new StubProcessingContext();
    private final EventMessage event = createEvent(0);

    @Test
    void initializeShouldCreateEntity() {
        when(entityFactory.create(1001, null, context)).thenReturn("entity(1001)");

        assertThat(evolver.initialize(1001, context)).isEqualTo("entity(1001)");
    }

    @Test
    void initializeShouldThrowExceptionWhenFactoryReturnsNull() {
        assertThatThrownBy(() -> evolver.initialize(1001, context)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void evolveShouldCreateThenEvolveEntity() {
        when(entityFactory.create(1001, event, context)).thenReturn("entity(1001)");
        when(entityEvolver.evolve("entity(1001)", event, context)).thenReturn("entity(1001)-1");

        assertThat(evolver.evolve(1001, null, event, context)).isEqualTo("entity(1001)-1");
    }

    @Test
    void evolveShouldOnlyEvolveEntityWhenInitializedAlready() {
        when(entityEvolver.evolve("entity(1001)", event, context)).thenReturn("entity(1001)-1");

        assertThat(evolver.evolve(1001, "entity(1001)", event, context)).isEqualTo("entity(1001)-1");

        verifyNoInteractions(entityFactory);
    }

    @Test
    void evolveDelegatesToEvolverWhenFactoryReturnsNull() {
        // The factory declines to create (returns null), so a static event sourcing handler in the evolver builds the
        // entity from a null state instead.
        when(entityEvolver.evolve(null, event, context)).thenReturn("created-from-null");

        assertThat(evolver.evolve(1001, null, event, context)).isEqualTo("created-from-null");
    }

    @Test
    void evolveReturnsNullWhenNeitherFactoryNorEvolverProducesState() {
        // The factory returns null (default mock) and the evolver leaves the state null (default mock): a null state
        // is a legitimate outcome, not an error.
        when(entityFactory.create(1001, event, context)).thenReturn(null);

        assertThat(evolver.evolve(1001, null, event, context)).isNull();
        verify(entityEvolver).evolve(null, event, context);
    }
}

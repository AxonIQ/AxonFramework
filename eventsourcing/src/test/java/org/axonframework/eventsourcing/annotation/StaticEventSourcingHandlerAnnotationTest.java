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

package org.axonframework.eventsourcing.annotation;

import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.annotation.AnnotationBasedEntityEvolvingComponent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;

/**
 * Verifies that a {@code static} {@link EventSourcingHandler} works with the current entity state as its first,
 * possibly {@code null}, argument, without requiring any additional annotation on that parameter.
 *
 * @author Mateusz Nowak
 */
class StaticEventSourcingHandlerAnnotationTest {

    private static final EventConverter converter = new DelegatingEventConverter(new JacksonConverter());
    private static final ClassBasedMessageTypeResolver messageTypeResolver = new ClassBasedMessageTypeResolver();

    private static final EntityEvolver<Account> EVOLVER =
            new AnnotationBasedEntityEvolvingComponent<>(Account.class, converter, messageTypeResolver);

    private record Opened(String id) {

    }

    private record Deposited(int amount) {

    }

    @SuppressWarnings("unused")
    private record Account(String id, int balance) {

        @EventSourcingHandler
        static Account on(@Nullable Account state, Opened event) {
            return new Account(event.id(), 0);
        }

        @EventSourcingHandler
        static Account on(@Nullable Account state, Deposited event) {
            return state == null ? null : new Account(state.id(), state.balance() + event.amount());
        }
    }

    @Test
    void staticEventSourcingHandlerCreatesEntityFromNullState() {
        // given
        EventMessage opened = asEventMessage(new Opened("acc-1"));

        // when
        Account result = EVOLVER.evolve(null, opened, StubProcessingContext.forMessage(opened));

        // then
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo("acc-1");
        assertThat(result.balance()).isZero();
    }

    @Test
    void staticEventSourcingHandlerEvolvesExistingStateAcrossEvents() {
        // given
        EventMessage opened = asEventMessage(new Opened("acc-1"));
        EventMessage deposited = asEventMessage(new Deposited(150));

        // when
        Account state = EVOLVER.evolve(null, opened, StubProcessingContext.forMessage(opened));
        state = EVOLVER.evolve(state, deposited, StubProcessingContext.forMessage(deposited));

        // then
        assertThat(state).isNotNull();
        assertThat(state.id()).isEqualTo("acc-1");
        assertThat(state.balance()).isEqualTo(150);
    }
}

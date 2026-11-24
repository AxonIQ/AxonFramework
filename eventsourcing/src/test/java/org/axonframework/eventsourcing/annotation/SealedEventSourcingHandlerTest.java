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
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.Event;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.modelling.EntityEvolver;
import org.axonframework.modelling.annotation.AnnotationBasedEntityEvolvingComponent;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;

/**
 * Test class validating that an {@link EventSourcingHandler @EventSourcingHandler} with a sealed parameter evolves the
 * entity for every concrete subtype, under the name of that subtype, converting the payload to the subtype.
 *
 * @author Mateusz Nowak
 */
class SealedEventSourcingHandlerTest {

    private static final AnnotationMessageTypeResolver TYPE_RESOLVER = new AnnotationMessageTypeResolver();

    @Nested
    class GivenAnEventSourcingHandlerWithASealedParameter {

        private final EntityEvolver<Account> testSubject = new AnnotationBasedEntityEvolvingComponent<>(
                Account.class,
                new DelegatingEventConverter(new JacksonConverter()),
                TYPE_RESOLVER,
                ClasspathParameterResolverFactory.forClass(Account.class),
                ClasspathHandlerDefinition.forClass(Account.class)
        );

        @Test
        void evolvesTheEntityForEverySubtypeUnderItsNameFromAJsonPayload() {
            // given
            var account = new Account();
            var opened = event(nameOf(AccountOpened.class), Map.of("id", "a1"));
            var deposited = event(nameOf(MoneyDeposited.class), Map.of("id", "a1", "amount", 5));

            // when
            account = testSubject.evolve(account, opened, StubProcessingContext.forMessage(opened));
            account = testSubject.evolve(account, deposited, StubProcessingContext.forMessage(deposited));

            // then
            assertThat(account.seen).containsExactly(new AccountOpened("a1"), new MoneyDeposited("a1", 5));
        }

        @Test
        void evolvesTheEntityForEverySubtypeUnderTheNamesOfARepeatedHandler() {
            // given
            var account = new Account();
            var opened = event(new QualifiedName("legacy", nameOf(AccountOpened.class).localName()), Map.of("id", "a1"));
            var deposited = event(nameOf(MoneyDeposited.class), Map.of("id", "a1", "amount", 5));

            // when
            account = testSubject.evolve(account, opened, StubProcessingContext.forMessage(opened));
            account = testSubject.evolve(account, deposited, StubProcessingContext.forMessage(deposited));

            // then
            assertThat(account.seen).containsExactly(new AccountOpened("a1"), new MoneyDeposited("a1", 5));
        }
    }

    private static QualifiedName nameOf(Class<?> type) {
        return TYPE_RESOLVER.resolveOrThrow(type).qualifiedName();
    }

    private static EventMessage event(QualifiedName name, Map<String, Object> payload) {
        return asEventMessage(new GenericMessage(new MessageType(name), payload));
    }

    sealed interface AccountEvent permits AccountOpened, MoneyDeposited {

    }

    @Event(namespace = "bank")
    record AccountOpened(String id) implements AccountEvent {

    }

    record MoneyDeposited(String id, int amount) implements AccountEvent {

    }

    static class Account {

        private final List<AccountEvent> seen = new ArrayList<>();

        @EventSourcingHandler
        @EventSourcingHandler(namespace = "legacy")
        void on(AccountEvent event) {
            seen.add(event);
        }
    }
}

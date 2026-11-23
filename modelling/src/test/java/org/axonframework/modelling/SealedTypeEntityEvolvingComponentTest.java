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

package org.axonframework.modelling;

import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link SimpleEntityEvolvingComponent#forSealedType(Class, java.util.function.BiFunction,
 * org.axonframework.messaging.core.MessageTypeResolver, EventConverter)}.
 *
 * @author Mateusz Nowak
 */
class SealedTypeEntityEvolvingComponentTest {

    private static final ClassBasedMessageTypeResolver RESOLVER = new ClassBasedMessageTypeResolver();
    private static final EventConverter CONVERTER = new DelegatingEventConverter(new JacksonConverter());

    sealed interface AccountEvent permits AccountOpened, MoneyDeposited {

    }

    record AccountOpened(String owner) implements AccountEvent {

    }

    record MoneyDeposited(int amount) implements AccountEvent {

    }

    record Account(String owner, int balance) {

        Account evolve(AccountEvent event) {
            return switch (event) {
                case AccountOpened opened -> new Account(opened.owner(), balance);
                case MoneyDeposited deposited -> new Account(owner, balance + deposited.amount());
            };
        }
    }

    private static QualifiedName nameOf(Class<?> type) {
        return RESOLVER.resolveOrThrow(type).qualifiedName();
    }

    private static EventMessage jsonEvent(Class<?> type, Map<String, Object> payload) {
        return new GenericEventMessage(new MessageType(nameOf(type)), payload);
    }

    @Nested
    class Evolving {

        private final SimpleEntityEvolvingComponent<Account> testSubject = SimpleEntityEvolvingComponent.forSealedType(
                AccountEvent.class, Account::evolve, RESOLVER, CONVERTER
        );

        @Test
        void evolvesForEachSubtypeByNameFromAJsonPayload() {
            // given
            EventMessage opened = jsonEvent(AccountOpened.class, Map.of("owner", "alice"));
            EventMessage deposited = jsonEvent(MoneyDeposited.class, Map.of("amount", 40));

            // when
            Account account = testSubject.evolve(
                    new Account(null, 0), opened, StubProcessingContext.forMessage(opened)
            );
            account = testSubject.evolve(account, deposited, StubProcessingContext.forMessage(deposited));

            // then
            assertThat(account).isEqualTo(new Account("alice", 40));
        }

        @Test
        void supportedEventsAreTheNamesOfTheSubtypes() {
            // then
            assertThat(testSubject.supportedEvents())
                    .containsExactlyInAnyOrder(nameOf(AccountOpened.class), nameOf(MoneyDeposited.class));
        }
    }

    @Nested
    class Rejecting {

        sealed interface Clashing permits First.Created, Second.Created {

        }

        static final class First {

            record Created() implements Clashing {

            }
        }

        static final class Second {

            record Created() implements Clashing {

            }
        }

        sealed interface Open permits Closed, Wide {

        }

        record Closed() implements Open {

        }

        non-sealed interface Wide extends Open {

        }

        @Test
        void aTypeThatIsNotSealed() {
            // when / then
            assertThatThrownBy(() -> SimpleEntityEvolvingComponent.<Account, MoneyDeposited>forSealedType(
                    MoneyDeposited.class, (account, event) -> account, RESOLVER, CONVERTER
            )).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining(MoneyDeposited.class.getName());
        }

        enum Color {
            RED,
            GREEN {
                @Override
                public String toString() {
                    return "green";
                }
            }
        }

        @Test
        void anEnumWithConstantBodies() {
            // when / then
            assertThatThrownBy(() -> SimpleEntityEvolvingComponent.<Account, Color>forSealedType(
                    Color.class, (account, event) -> account, RESOLVER, CONVERTER
            )).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("not a sealed type");
        }

        @Test
        void aHierarchyWithAnOpenBranch() {
            // when / then
            assertThatThrownBy(() -> SimpleEntityEvolvingComponent.<Account, Open>forSealedType(
                    Open.class, (account, event) -> account, RESOLVER, CONVERTER
            )).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining(Wide.class.getName());
        }

        @Test
        void twoSubtypesResolvingToTheSameName() {
            // when / then
            assertThatThrownBy(() -> SimpleEntityEvolvingComponent.<Account, Clashing>forSealedType(
                    Clashing.class, (account, event) -> account, RESOLVER, CONVERTER
            )).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining(nameOf(First.Created.class).fullName());
        }

        @Test
        void nullArguments() {
            // when / then
            assertThatThrownBy(() -> SimpleEntityEvolvingComponent.<Account, AccountEvent>forSealedType(
                    null, Account::evolve, RESOLVER, CONVERTER
            )).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> SimpleEntityEvolvingComponent.<Account, AccountEvent>forSealedType(
                    AccountEvent.class, null, RESOLVER, CONVERTER
            )).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> SimpleEntityEvolvingComponent.<Account, AccountEvent>forSealedType(
                    AccountEvent.class, Account::evolve, null, CONVERTER
            )).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> SimpleEntityEvolvingComponent.<Account, AccountEvent>forSealedType(
                    AccountEvent.class, Account::evolve, RESOLVER, null
            )).isInstanceOf(NullPointerException.class);
        }
    }
}

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

package org.axonframework.modelling.entity.annotation;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;

/**
 * Tests that an entity's {@code @EventHandler} taking a sealed event type evolves the entity for every permitted
 * subtype, routed by the event's name and converted from a JSON {@link Map} payload.
 *
 * @author Mateusz Nowak
 */
class SealedEntityEvolutionTest {

    private static final ClassBasedMessageTypeResolver RESOLVER = new ClassBasedMessageTypeResolver();

    private static <E> AnnotatedEntityMetamodel<E> metamodel(Class<E> type) {
        return AnnotatedEntityMetamodel.forConcreteType(
                type,
                ClasspathParameterResolverFactory.forClass(SealedEntityEvolutionTest.class),
                ClasspathHandlerDefinition.forClass(SealedEntityEvolutionTest.class),
                RESOLVER,
                new DelegatingMessageConverter(new JacksonConverter()),
                new DelegatingEventConverter(new JacksonConverter())
        );
    }

    private static EventMessage jsonEvent(String name, Map<String, Object> payload) {
        return asEventMessage(new GenericMessage(new MessageType(new QualifiedName(name)), payload));
    }

    private static <E> E evolve(AnnotatedEntityMetamodel<E> metamodel, E entity, EventMessage event) {
        return metamodel.evolve(entity, event, StubProcessingContext.forMessage(event));
    }

    private static String nameOf(Class<?> type) {
        return RESOLVER.resolveOrThrow(type).qualifiedName().fullName();
    }

    sealed interface AccountEvent permits AccountOpened, MoneyDeposited {

    }

    record AccountOpened(String owner) implements AccountEvent {

    }

    record MoneyDeposited(int amount) implements AccountEvent {

    }

    record Account(String owner, int balance) {

        @EventHandler
        Account on(AccountEvent event) {
            return switch (event) {
                case AccountOpened opened -> new Account(opened.owner(), balance);
                case MoneyDeposited deposited -> new Account(owner, balance + deposited.amount());
            };
        }
    }

    @Nested
    class SealedParameter {

        @Test
        void everySubtypeEvolvesTheEntityWhenSentUnderItsNameWithAJsonPayload() {
            // given
            var metamodel = metamodel(Account.class);
            Account account = new Account(null, 0);

            // when
            account = evolve(metamodel, account, jsonEvent(nameOf(AccountOpened.class), Map.of("owner", "alice")));
            account = evolve(metamodel, account, jsonEvent(nameOf(MoneyDeposited.class), Map.of("amount", 40)));

            // then
            assertThat(account).isEqualTo(new Account("alice", 40));
        }
    }

    record LegacyAccount(int balance) {

        @EventHandler(namespace = "legacy")
        LegacyAccount on(AccountEvent event) {
            return switch (event) {
                case AccountOpened opened -> this;
                case MoneyDeposited deposited -> new LegacyAccount(balance + deposited.amount());
            };
        }
    }

    @Nested
    class DeclaredNamespace {

        @Test
        void namespaceAppliesToEachSubtype() {
            // given
            var metamodel = metamodel(LegacyAccount.class);

            // when
            LegacyAccount evolved = evolve(metamodel, new LegacyAccount(0),
                                           jsonEvent("legacy.MoneyDeposited", Map.of("amount", 5)));
            LegacyAccount ignored = evolve(metamodel, new LegacyAccount(0),
                                           jsonEvent(nameOf(MoneyDeposited.class), Map.of("amount", 5)));

            // then
            assertThat(evolved).isEqualTo(new LegacyAccount(5));
            assertThat(ignored).isEqualTo(new LegacyAccount(0));
        }
    }

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

    record DuplicateNames(int count) {

        @EventHandler
        DuplicateNames on(Clashing event) {
            return this;
        }
    }

    @Nested
    class DuplicateSubtypeNames {

        @Test
        void twoSubtypesResolvingToTheSameNameAreRejected() {
            // when / then
            assertThatThrownBy(() -> metamodel(DuplicateNames.class))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("different payload types");
        }
    }
}

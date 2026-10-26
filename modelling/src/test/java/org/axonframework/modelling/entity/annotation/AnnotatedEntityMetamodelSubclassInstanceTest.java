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

import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.junit.jupiter.api.*;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;

/**
 * Tests how the {@link AnnotatedEntityMetamodel} of a non-polymorphic entity type treats an instance of a subclass it
 * does not know, such as a proxy: it applies no handlers of that subclass and neither fails nor changes the handling of
 * commands, including for child entities holding such an instance.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
class AnnotatedEntityMetamodelSubclassInstanceTest {

    private record Deposited(int amount) {

    }

    private record InterestPaid(int amount) {

    }

    private record Withdraw() {

    }

    private record Pay() {

    }

    static class Account {

        int balance;

        @EventHandler
        void on(Deposited event) {
            balance += event.amount();
        }

        @CommandHandler
        void handle(Withdraw command) {
            balance -= 1;
        }
    }

    static class Savings extends Account {

        int interest;

        @EventHandler
        void on(InterestPaid event) {
            interest += event.amount();
        }
    }

    static class HandlerFreeSavings extends Account {

    }

    static class Payment {

        int received;

        @EventHandler
        void on(Deposited event) {
            received += event.amount();
        }

        @CommandHandler
        String handle(Pay command) {
            return "paid";
        }
    }

    static class CardPayment extends Payment {

    }

    static class Order {

        int total;

        @EntityMember
        private Payment payment = new CardPayment();

        @EventHandler
        void on(Deposited event) {
            total += event.amount();
        }
    }

    static class Invoice {

        @CommandHandler
        String handle(Pay command) {
            return "invoiced";
        }
    }

    static class ProxiedInvoice extends Invoice {

    }

    private static <T> AnnotatedEntityMetamodel<T> concrete(Class<T> type) {
        return AnnotatedEntityMetamodel.forConcreteType(
                type,
                ClasspathParameterResolverFactory.forClass(AnnotatedEntityMetamodelSubclassInstanceTest.class),
                ClasspathHandlerDefinition.forClass(AnnotatedEntityMetamodelSubclassInstanceTest.class),
                new ClassBasedMessageTypeResolver(),
                new DelegatingMessageConverter(new JacksonConverter()),
                new DelegatingEventConverter(new JacksonConverter())
        );
    }

    private final AnnotatedEntityMetamodel<Account> metamodel = AnnotatedEntityMetamodel.forConcreteType(
            Account.class,
            ClasspathParameterResolverFactory.forClass(AnnotatedEntityMetamodelSubclassInstanceTest.class),
            ClasspathHandlerDefinition.forClass(AnnotatedEntityMetamodelSubclassInstanceTest.class),
            new ClassBasedMessageTypeResolver(),
            new DelegatingMessageConverter(new JacksonConverter()),
            new DelegatingEventConverter(new JacksonConverter())
    );

    private Account evolve(Account entity) {
        var event = asEventMessage(new Deposited(10));
        return metamodel.evolve(entity, event, StubProcessingContext.forMessage(event));
    }

    @Test
    void evolvesInstanceOfTheEntityTypeItself() {
        // when
        var result = evolve(new Account());

        // then
        assertThat(result.balance).isEqualTo(10);
    }

    @Test
    void ignoresInstanceOfUnknownSubclass() {
        // given
        var savings = new HandlerFreeSavings();

        // when
        var result = evolve(savings);

        // then
        assertThat(result).isSameAs(savings);
        assertThat(result.balance).isEqualTo(0);
    }

    @Test
    void handlesCommandForInstanceOfUnknownSubclass() {
        // given
        var command = new GenericCommandMessage(new MessageType(Withdraw.class), new Withdraw());
        var savings = new Savings();

        // when
        metamodel.handleInstance(command, savings, StubProcessingContext.forMessage(command))
                 .first()
                 .asCompletableFuture()
                 .orTimeout(5, TimeUnit.SECONDS)
                 .join();

        // then
        assertThat(savings.balance).isEqualTo(-1);
    }

    @Test
    void evolvesSubclassInstanceOfEntityWithoutEventHandlers() {
        // given
        var invoices = concrete(Invoice.class);
        var event = asEventMessage(new Deposited(10));

        // when
        var result = invoices.evolve(new ProxiedInvoice(), event, StubProcessingContext.forMessage(event));

        // then
        assertThat(result).isInstanceOf(ProxiedInvoice.class);
    }

    @Test
    void evolvesParentWhoseChildEntityHoldsASubclassOfTheDeclaredChildType() {
        // given
        var orders = concrete(Order.class);
        var order = new Order();
        var event = asEventMessage(new Deposited(10));

        // when
        var result = orders.evolve(order, event, StubProcessingContext.forMessage(event));

        // then
        assertThat(result.total).isEqualTo(10);
    }

    @Test
    void routesCommandToChildEntityHoldingASubclassOfTheDeclaredChildType() {
        // given
        var orders = concrete(Order.class);
        var command = new GenericCommandMessage(new MessageType(Pay.class), new Pay());

        // when
        var result = orders.handleInstance(command, new Order(), StubProcessingContext.forMessage(command))
                           .first()
                           .asCompletableFuture()
                           .orTimeout(5, TimeUnit.SECONDS)
                           .join();

        // then
        assertThat(result.message().payload()).isEqualTo("paid");
    }
}

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

package org.axonframework.messaging.eventhandling.annotation;

import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.commandhandling.annotation.AnnotatedCommandHandlingComponent;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.UnsupportedHandlerException;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.interception.annotation.EventHandlerInterceptor;
import org.axonframework.messaging.queryhandling.annotation.AnnotatedQueryHandlingComponent;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;

/**
 * Test class validating that an event handler with a sealed parameter type is subscribed to the name of every concrete
 * permitted subtype, and receives the payload converted to the subtype registered for the name of the event.
 */
class SealedEventHandlerTest {

    private static final AnnotationMessageTypeResolver TYPE_RESOLVER = new AnnotationMessageTypeResolver();

    private static final QualifiedName ACCOUNT_OPENED = nameOf(AccountOpened.class);
    private static final QualifiedName MONEY_DEPOSITED = nameOf(MoneyDeposited.class);
    private static final QualifiedName FEE_CHARGED = nameOf(FeeCharged.class);
    private static final QualifiedName INTEREST_PAID = nameOf(InterestPaid.class);

    @Nested
    class GivenAHandlerForASealedInterface {

        @Test
        void subscribesToTheNameOfEveryConcreteSubtypeRecursingThroughNestedSealedTypes() {
            // given
            var handler = new AccountHandler();

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactlyInAnyOrder(
                    ACCOUNT_OPENED, MONEY_DEPOSITED, FEE_CHARGED, INTEREST_PAID
            );
        }

        @Test
        void convertsEachEventToTheSubtypeRegisteredForItsName() {
            // given
            var handler = new AccountHandler();
            var component = componentFor(handler);

            // when
            handle(component, event(ACCOUNT_OPENED, Map.of("id", "a1")));
            handle(component, event(MONEY_DEPOSITED, Map.of("id", "a1", "amount", 5)));
            handle(component, event(FEE_CHARGED, Map.of("id", "a2")));
            handle(component, event(INTEREST_PAID, Map.of("id", "a3")));

            // then
            assertThat(handler.handled).containsExactly(
                    new AccountOpened("a1"),
                    new MoneyDeposited("a1", 5),
                    new FeeCharged("a2"),
                    new InterestPaid("a3")
            );
        }
    }

    @Nested
    class GivenSubtypesWithAndWithoutAnEventAnnotation {

        @Test
        void usesTheEventNameWhenPresentAndTheClassBasedNameOtherwise() {
            // given
            var handler = new AccountHandler();

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).contains(
                    new QualifiedName("bank", "AccountOpened"),
                    new QualifiedName("bank", "Fee"),
                    new QualifiedName(MoneyDeposited.class.getPackageName(), "MoneyDeposited")
            );
        }
    }

    @Nested
    class GivenDeclaredNameParts {

        @Test
        void appliesADeclaredNamespaceToEverySubtype() {
            // given
            var handler = new NamespaceHandler();

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactlyInAnyOrder(
                    new QualifiedName("legacy", ACCOUNT_OPENED.localName()),
                    new QualifiedName("legacy", MONEY_DEPOSITED.localName()),
                    new QualifiedName("legacy", FEE_CHARGED.localName()),
                    new QualifiedName("legacy", INTEREST_PAID.localName())
            );
        }

        @Test
        void keepsOneSingleNameWhenANameIsDeclared() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(namespace = "legacy", name = "AccountChanged")
                void on(AccountEvent event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactly(new QualifiedName("legacy", "AccountChanged"));
        }

        @Test
        void keepsOneSingleNameWhenAnEventNameIsDeclared() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(eventName = "legacy.AccountChanged")
                void on(AccountEvent event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactly(new QualifiedName("legacy.AccountChanged"));
        }
    }

    @Nested
    class GivenARepeatedAnnotation {

        @Test
        void expandsEveryOccurrenceOnItsOwn() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler
                @EventHandler(namespace = "legacy")
                void on(PaymentEvent event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactlyInAnyOrder(
                    nameOf(PaymentAuthorized.class),
                    nameOf(PaymentCaptured.class),
                    new QualifiedName("legacy", nameOf(PaymentAuthorized.class).localName()),
                    new QualifiedName("legacy", nameOf(PaymentCaptured.class).localName())
            );
        }
    }

    @Nested
    class GivenAnOpenHierarchy {

        @Test
        void rejectsANonSealedPermittedSubtypeAtStartupNamingTheType() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler
                void on(OpenEvent event) {
                    // Unimportant
                }
            };

            // when / then
            assertThatThrownBy(() -> componentFor(handler))
                    .isInstanceOf(UnsupportedHandlerException.class)
                    .hasMessageContaining(LooseEvent.class.getName());
        }
    }

    @Nested
    class GivenSubtypesResolvingToTheSameName {

        @Test
        void rejectsTwoSubtypesWithTheSameName() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler
                void on(SameNameEvent event) {
                    // Unimportant
                }
            };

            // when / then
            assertThatThrownBy(() -> componentFor(handler))
                    .isInstanceOf(UnsupportedHandlerException.class)
                    .hasMessageContaining("x.Same")
                    .hasMessageContaining(SameNameOne.class.getName())
                    .hasMessageContaining("@Event(name = ...)");
        }

        @Test
        void rejectsACollisionCausedByANamespaceOverride() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(namespace = "legacy")
                void on(NamespaceCollisionEvent event) {
                    // Unimportant
                }
            };

            // when / then
            assertThatThrownBy(() -> componentFor(handler))
                    .isInstanceOf(UnsupportedHandlerException.class)
                    .hasMessageContaining("legacy.Created");
        }

        @Test
        void acceptsTheSameSubtypesWithoutTheNamespaceOverride() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler
                void on(NamespaceCollisionEvent event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactlyInAnyOrder(
                    new QualifiedName("a", "Created"), new QualifiedName("b", "Created")
            );
        }
    }

    @Nested
    class GivenEnums {

        @Test
        void keepsTodaysBehaviourForAnEnumParameter() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler
                void on(Color event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactly(nameOf(Color.class));
        }

        @Test
        void countsAnEnumPermittedSubtypeAsOneName() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler
                void on(Status event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactlyInAnyOrder(nameOf(Paid.class), nameOf(Level.class));
        }
    }

    @Nested
    class GivenACommandHandler {

        @Test
        void keepsTodaysBehaviourForASealedParameter() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @CommandHandler
                void handle(AccountEvent command) {
                    // Unimportant
                }
            };

            // when
            var component = new AnnotatedCommandHandlingComponent<>(
                    handler,
                    ClasspathParameterResolverFactory.forClass(handler.getClass()),
                    ClasspathHandlerDefinition.forClass(handler.getClass()),
                    TYPE_RESOLVER,
                    new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
            );

            // then
            assertThat(component.supportedCommands()).containsExactly(nameOf(AccountEvent.class));
        }
    }

    @Nested
    class GivenAnInterceptor {

        @Test
        void buildsForAnOpenHierarchy() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandlerInterceptor
                void audit(OpenEvent event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).isEmpty();
        }

        @Test
        void runsOncePerEventNextToASealedHandler() {
            // given
            var log = new ArrayList<String>();
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandlerInterceptor
                void audit(AccountEvent event) {
                    log.add("audit");
                }

                @SuppressWarnings("unused")
                @EventHandler
                void on(AccountEvent event) {
                    log.add("handler");
                }
            };
            var component = componentFor(handler);

            // when
            handle(component, event(ACCOUNT_OPENED, Map.of("id", "a1")));

            // then
            assertThat(log).containsExactly("audit", "handler");
        }
    }

    @Nested
    class GivenAQueryHandler {

        @Test
        void keepsTodaysBehaviourForASealedParameter() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @QueryHandler
                String handle(AccountEvent query) {
                    return "";
                }
            };

            // when
            var component = new AnnotatedQueryHandlingComponent<>(
                    handler,
                    ClasspathParameterResolverFactory.forClass(handler.getClass()),
                    ClasspathHandlerDefinition.forClass(handler.getClass()),
                    TYPE_RESOLVER,
                    new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
            );

            // then
            assertThat(component.supportedQueries()).containsExactly(nameOf(AccountEvent.class));
        }
    }

    @Nested
    class GivenAConcreteSealedClass {

        @Test
        void subscribesToItsOwnNameAndItsSubclassesNames() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler
                void on(Base event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactlyInAnyOrder(
                    nameOf(Base.class), nameOf(Derived.class)
            );
        }
    }

    @Nested
    class GivenAnEventNameNextToTheExpansion {

        @Test
        void subscribesToTheSubtypeNamesPlusTheDeclaredEventName() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler
                @EventHandler(eventName = "x.Old")
                void on(PaymentEvent event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactlyInAnyOrder(
                    nameOf(PaymentAuthorized.class), nameOf(PaymentCaptured.class), new QualifiedName("x.Old")
            );
        }
    }

    private static QualifiedName nameOf(Class<?> type) {
        return TYPE_RESOLVER.resolveOrThrow(type).qualifiedName();
    }

    private static AnnotatedEventHandlingComponent<?> componentFor(Object handler) {
        return new AnnotatedEventHandlingComponent<>(
                handler,
                ClasspathParameterResolverFactory.forClass(handler.getClass()),
                ClasspathHandlerDefinition.forClass(handler.getClass()),
                TYPE_RESOLVER,
                new DelegatingEventConverter(new JacksonConverter())
        );
    }

    private static void handle(AnnotatedEventHandlingComponent<?> component, EventMessage event) {
        var result = component.handle(event, StubProcessingContext.forMessage(event));
        assertThat(result.error()).isEmpty();
    }

    private static EventMessage event(QualifiedName name, Map<String, Object> payload) {
        return asEventMessage(new GenericMessage(new MessageType(name), payload));
    }

    sealed interface AccountEvent permits AccountOpened, MoneyDeposited, Adjustment {

    }

    sealed interface Adjustment extends AccountEvent permits FeeCharged, InterestPaid {

    }

    @Event(namespace = "bank")
    record AccountOpened(String id) implements AccountEvent {

    }

    record MoneyDeposited(String id, int amount) implements AccountEvent {

    }

    @Event(namespace = "bank", name = "Fee")
    record FeeCharged(String id) implements Adjustment {

    }

    record InterestPaid(String id) implements Adjustment {

    }

    sealed interface PaymentEvent permits PaymentAuthorized, PaymentCaptured {

    }

    @Event(namespace = "pay")
    record PaymentAuthorized(String id) implements PaymentEvent {

    }

    @Event(namespace = "pay")
    record PaymentCaptured(String id) implements PaymentEvent {

    }

    sealed interface OpenEvent permits ClosedEvent, LooseEvent {

    }

    record ClosedEvent() implements OpenEvent {

    }

    non-sealed interface LooseEvent extends OpenEvent {

    }

    sealed interface SameNameEvent permits SameNameOne, SameNameTwo {

    }

    @Event(namespace = "x", name = "Same")
    record SameNameOne() implements SameNameEvent {

    }

    @Event(namespace = "x", name = "Same")
    record SameNameTwo() implements SameNameEvent {

    }

    sealed interface NamespaceCollisionEvent permits FirstCreated, SecondCreated {

    }

    @Event(namespace = "a", name = "Created")
    record FirstCreated() implements NamespaceCollisionEvent {

    }

    @Event(namespace = "b", name = "Created")
    record SecondCreated() implements NamespaceCollisionEvent {

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

    sealed interface Status permits Paid, Level {

    }

    record Paid() implements Status {

    }

    enum Level implements Status {
        LOW,
        HIGH
    }

    sealed static class Base permits Derived {

    }

    static final class Derived extends Base {

    }

    static class AccountHandler {

        final List<AccountEvent> handled = new ArrayList<>();

        @EventHandler
        void on(AccountEvent event) {
            handled.add(event);
        }
    }

    static class NamespaceHandler {

        @EventHandler(namespace = "legacy")
        void on(AccountEvent event) {
            // Unimportant
        }
    }
}

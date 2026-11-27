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
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.NamespaceMessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.UnsupportedHandlerException;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.junit.jupiter.api.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;

/**
 * Test class validating that a method repeating the {@link EventHandler @EventHandler} annotation is registered as an
 * event handler for every occurrence, through the {@link AnnotatedEventHandlingComponent}.
 *
 * @author Allard Buijze
 */
class RepeatedEventHandlerTest {

    private static final QualifiedName ORDERS_ORDER_PLACED = new QualifiedName("orders", "OrderPlaced");
    private static final QualifiedName LEGACY_ORDER_PLACED = new QualifiedName("legacy", "OrderPlaced");

    @Nested
    class GivenAMethodWithRepeatedEventHandlers {

        @Test
        void subscribesTheMethodForEveryOccurrence() {
            // given
            var handler = new RepeatedHandler();

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactlyInAnyOrder(ORDERS_ORDER_PLACED,
                                                                               LEGACY_ORDER_PLACED);
        }

        @Test
        void invokesTheMethodForAnEventOfEitherName() {
            // given
            var handler = new RepeatedHandler();
            var component = componentFor(handler);

            // when
            handle(component, orderPlaced(ORDERS_ORDER_PLACED, "order-1"));
            handle(component, orderPlaced(LEGACY_ORDER_PLACED, "order-2"));

            // then
            assertThat(handler.handled).containsExactly("order-1", "order-2");
        }

        @Test
        void invokesTheMethodOnceWhenOccurrencesResolveToTheSameName() {
            // given
            var handler = new DuplicateNameHandler();
            var component = componentFor(handler);

            // when
            handle(component, orderPlaced(ORDERS_ORDER_PLACED, "order-1"));

            // then
            assertThat(component.supportedEvents()).containsExactly(ORDERS_ORDER_PLACED);
            assertThat(handler.handled).containsExactly("order-1");
        }
    }

    @Nested
    class GivenARepeatableCustomAnnotationMetaAnnotatedWithEventHandler {

        @Test
        void subscribesTheMethodForEveryOccurrence() {
            // given
            var handler = new CustomAnnotationHandler();

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactlyInAnyOrder(ORDERS_ORDER_PLACED,
                                                                               LEGACY_ORDER_PLACED);
        }
    }

    @Nested
    class GivenACustomAnnotationRepeatingEventHandler {

        @Test
        void subscribesTheMethodForEveryEventHandlerOnTheAnnotation() {
            // given
            var handler = new ComposedAnnotationHandler();

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactlyInAnyOrder(ORDERS_ORDER_PLACED,
                                                                               LEGACY_ORDER_PLACED);
        }

        @Test
        void invokesTheMethodForAnEventOfEitherName() {
            // given
            var handler = new ComposedAnnotationHandler();
            var component = componentFor(handler);

            // when
            handle(component, orderPlaced(ORDERS_ORDER_PLACED, "order-1"));
            handle(component, orderPlaced(LEGACY_ORDER_PLACED, "order-2"));

            // then
            assertThat(handler.handled).containsExactly("order-1", "order-2");
        }

        @Test
        void overridesTheAttributesOfEveryEventHandlerWithThoseOfTheAnnotation() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @OrderHandler(name = "OrderCreated")
                void on(OrderPlaced event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactlyInAnyOrder(
                    new QualifiedName("orders", "OrderCreated"),
                    new QualifiedName("legacy", "OrderCreated")
            );
        }

        @Test
        void combinesTheEventHandlersOfTheAnnotationWithThoseOnTheMethod() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @OrderPlacedHandler
                @EventHandler(namespace = "archive")
                void on(OrderPlaced event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactlyInAnyOrder(
                    ORDERS_ORDER_PLACED,
                    LEGACY_ORDER_PLACED,
                    new QualifiedName("archive", "OrderPlaced")
            );
        }
    }

    @Nested
    class GivenNamespaceAndNameAttributes {

        @Test
        void usesTheNamespaceAndNameAsDefined() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(namespace = "legacy", name = "OrderCreated")
                void on(OrderPlaced event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactly(new QualifiedName("legacy", "OrderCreated"));
        }

        @Test
        void derivesAnUndefinedNamespaceFromThePayloadType() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(name = "OrderCreated")
                void on(OrderPlaced event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactly(new QualifiedName("orders", "OrderCreated"));
        }

        @Test
        void derivesAnUndefinedNameFromThePayloadType() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(namespace = "legacy")
                void on(OrderPlaced event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactly(LEGACY_ORDER_PLACED);
        }

        @Test
        void addsTheNameOfThePayloadTypeThroughAnEventHandlerWithoutAttributes() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler
                @EventHandler(namespace = "legacy")
                void on(OrderPlaced event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            // declared names replace the payload type's name, so only the bare annotation keeps it
            assertThat(component.supportedEvents()).containsExactlyInAnyOrder(ORDERS_ORDER_PLACED,
                                                                               LEGACY_ORDER_PLACED);
        }

        @Test
        void usesTheEventNameAsIs() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(eventName = "legacy.OrderCreated")
                void on(OrderPlaced event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactly(new QualifiedName("legacy.OrderCreated"));
        }

        @Test
        void rejectsAnEventNameCombinedWithANamespace() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(eventName = "legacy.OrderCreated", namespace = "legacy")
                void on(OrderPlaced event) {
                    // Unimportant
                }
            };

            // when / then
            assertThatThrownBy(() -> componentFor(handler))
                    .isInstanceOf(UnsupportedHandlerException.class)
                    .hasMessageContaining("eventName");
        }
    }

    @Nested
    class GivenANameWithoutANamespace {

        private static final QualifiedName BARE_ORDER_PLACED = new QualifiedName("OrderPlaced");

        @Test
        void subscribesToTheNameAsIsThroughTheEventName() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(eventName = "OrderPlaced")
                void on(OrderPlaced event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactly(BARE_ORDER_PLACED);
        }

        @Test
        void invokesTheMethodForAnEventStoredUnderTheNameThroughTheEventName() {
            // given
            List<String> handled = new ArrayList<>();
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(eventName = "OrderPlaced")
                void on(OrderPlaced event) {
                    handled.add(event.orderId());
                }
            };
            var component = componentFor(handler);

            // when
            handle(component, orderPlaced(BARE_ORDER_PLACED, "order-1"));

            // then
            assertThat(handled).containsExactly("order-1");
        }

        @Test
        void cannotExpressItThroughAnEmptyNamespace() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(namespace = "", name = "OrderPlaced")
                void on(OrderPlaced event) {
                    // Unimportant
                }
            };

            // when
            var component = componentFor(handler);

            // then
            // an empty namespace is the attribute's default, so the namespace of the payload type applies
            assertThat(component.supportedEvents()).containsExactly(ORDERS_ORDER_PLACED);
        }

        @Test
        void subscribesAHandlerWithoutAttributesWhenTheResolverMapsThePayloadTypeToIt() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler
                void on(OrderPlaced event) {
                    // Unimportant
                }
            };
            var resolver = NamespaceMessageTypeResolver.namespace("")
                                                       .message(OrderPlaced.class, "OrderPlaced", "0.0.1")
                                                       .fallback(new AnnotationMessageTypeResolver());

            // when
            var component = new AnnotatedEventHandlingComponent<>(
                    handler,
                    ClasspathParameterResolverFactory.forClass(handler.getClass()),
                    ClasspathHandlerDefinition.forClass(handler.getClass()),
                    resolver,
                    new DelegatingEventConverter(PassThroughConverter.INSTANCE)
            );

            // then
            assertThat(component.supportedEvents()).containsExactly(BARE_ORDER_PLACED);
        }
    }

    @Nested
    class GivenRepeatedEventHandlersInASuperclass {

        @Test
        void keepsEveryOccurrenceWhenTheMethodIsInherited() {
            // given
            var handler = new InheritingHandler();

            // when
            var component = componentFor(handler);

            // then
            assertThat(component.supportedEvents()).containsExactlyInAnyOrder(ORDERS_ORDER_PLACED,
                                                                               LEGACY_ORDER_PLACED);
        }

        @Test
        void replacesEveryOccurrenceWhenTheMethodIsOverridden() {
            // given
            var handler = new OverridingHandler();
            var component = componentFor(handler);

            // when
            handle(component, orderPlaced(new QualifiedName("renamed", "OrderPlaced"), "order-1"));

            // then
            assertThat(component.supportedEvents()).containsExactly(new QualifiedName("renamed", "OrderPlaced"));
            assertThat(handler.handled).containsExactly("overridden:order-1");
        }
    }

    private static AnnotatedEventHandlingComponent<?> componentFor(Object handler) {
        return new AnnotatedEventHandlingComponent<>(
                handler,
                ClasspathParameterResolverFactory.forClass(handler.getClass()),
                ClasspathHandlerDefinition.forClass(handler.getClass()),
                new AnnotationMessageTypeResolver(),
                new DelegatingEventConverter(PassThroughConverter.INSTANCE)
        );
    }

    private static void handle(AnnotatedEventHandlingComponent<?> component, EventMessage event) {
        var result = component.handle(event, StubProcessingContext.forMessage(event));
        assertThat(result.error()).isEmpty();
    }

    private static EventMessage orderPlaced(QualifiedName name, String orderId) {
        return asEventMessage(new GenericMessage(new MessageType(name), new OrderPlaced(orderId)));
    }

    @Event(namespace = "orders", name = "OrderPlaced")
    record OrderPlaced(String orderId) {

    }

    static class RepeatedHandler {

        final List<String> handled = new ArrayList<>();

        @EventHandler(namespace = "orders", name = "OrderPlaced")
        @EventHandler(namespace = "legacy", name = "OrderPlaced")
        void on(OrderPlaced event) {
            handled.add(event.orderId());
        }
    }

    static class DuplicateNameHandler {

        final List<String> handled = new ArrayList<>();

        // Both occurrences resolve to orders.OrderPlaced, the second one through the payload type
        @EventHandler(namespace = "orders", name = "OrderPlaced")
        @EventHandler
        void on(OrderPlaced event) {
            handled.add(event.orderId());
        }
    }

    // Deliberately package-private, as the framework has to read the container without access to its members
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @EventHandler
    @Repeatable(OrderEvents.class)
    @interface OrderEvent {

        String namespace();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface OrderEvents {

        OrderEvent[] value();
    }

    static class CustomAnnotationHandler {

        @SuppressWarnings("unused")
        @OrderEvent(namespace = "orders")
        @OrderEvent(namespace = "legacy")
        void on(OrderPlaced event) {
            // Unimportant
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @EventHandler(namespace = "orders")
    @EventHandler(namespace = "legacy")
    @interface OrderPlacedHandler {

    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @EventHandler(namespace = "orders")
    @EventHandler(namespace = "legacy")
    @interface OrderHandler {

        String name();
    }

    static class ComposedAnnotationHandler {

        final List<String> handled = new ArrayList<>();

        @OrderPlacedHandler
        void on(OrderPlaced event) {
            handled.add(event.orderId());
        }
    }

    static class InheritingHandler extends RepeatedHandler {

    }

    static class OverridingHandler extends RepeatedHandler {

        @Override
        @EventHandler(namespace = "renamed")
        void on(OrderPlaced event) {
            handled.add("overridden:" + event.orderId());
        }
    }
}

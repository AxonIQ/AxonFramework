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
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.core.annotation.QualifiedName;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.core.sequencing.MetadataSequencingPolicy;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating how the {@link AnnotatedEventHandlingComponent} derives the names of the events an
 * {@link EventHandler} annotated method handles, through the {@link EventHandler#event()} and the deprecated
 * {@link EventHandler#eventName()} attributes.
 *
 * @author Allard Buijze
 */
class AnnotatedEventHandlingComponentEventNamesTest {

    /**
     * A {@link MessageTypeResolver} that cannot resolve any payload type, used to validate that declared names do not
     * require resolving the payload type.
     */
    private static final MessageTypeResolver UNRESOLVABLE = payloadType -> Optional.empty();

    private final AtomicInteger callCount = new AtomicInteger();

    @Event(namespace = "com.acme")
    private record OrderPlacedEvent(String id) {

    }

    @Namespace("billing")
    @Event
    private record InvoicePaid(String id) {

    }

    @Event(namespace = "orders", name = "v2.OrderPlaced")
    private record VersionedOrderPlaced(String id) {

    }

    @Nested
    class EventAttribute {

        @Test
        void qualifiedNameMatchingThePayloadTypeIsSupported() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(event = @QualifiedName(namespace = "com.acme", name = "OrderPlacedEvent"))
                public void handle(OrderPlacedEvent event) {
                    // no-op
                }
            };

            // when
            var supportedEvents = namesSupportedBy(annotatedEventHandlingComponent(handler));

            // then
            assertThat(supportedEvents).containsExactly("com.acme.OrderPlacedEvent");
        }

        @Test
        void qualifiedNameDifferingFromThePayloadTypeReplacesPayloadTypeName() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(event = @QualifiedName(namespace = "com.acme.legacy", name = "OrderPlaced"))
                public void handle(OrderPlacedEvent event) {
                    callCount.incrementAndGet();
                }
            };
            var component = annotatedEventHandlingComponent(handler);
            var legacy = orderPlacedNamed("com.acme.legacy.OrderPlaced");
            var current = orderPlacedNamed("com.acme.OrderPlacedEvent");

            // when
            var legacyResult = component.handle(legacy, StubProcessingContext.forMessage(legacy));
            var currentResult = component.handle(current, StubProcessingContext.forMessage(current));

            // then
            assertThat(namesSupportedBy(component)).containsExactly("com.acme.legacy.OrderPlaced");
            assertSuccessfulStream(legacyResult);
            assertThat(currentResult.error()).isPresent();
            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        void allQualifiedNamesAreSupportedAndRoutedToTheSameHandler() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(event = {
                        @QualifiedName(namespace = "com.acme", name = "OrderPlacedEvent"),
                        @QualifiedName(namespace = "com.acme.legacy", name = "OrderPlaced")
                })
                public void handle(OrderPlacedEvent event) {
                    callCount.incrementAndGet();
                }
            };
            var component = annotatedEventHandlingComponent(handler);
            var current = orderPlacedNamed("com.acme.OrderPlacedEvent");
            var legacy = orderPlacedNamed("com.acme.legacy.OrderPlaced");

            // when
            var currentResult = component.handle(current, StubProcessingContext.forMessage(current));
            var legacyResult = component.handle(legacy, StubProcessingContext.forMessage(legacy));

            // then
            assertThat(namesSupportedBy(component)).containsExactlyInAnyOrder(
                    "com.acme.OrderPlacedEvent",
                    "com.acme.legacy.OrderPlaced"
            );
            assertSuccessfulStream(currentResult);
            assertSuccessfulStream(legacyResult);
            assertThat(callCount.get()).isEqualTo(2);
        }

        @Test
        void allQualifiedNamesAreRoutedThroughTheCustomSequencingPolicyOfTheHandler() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @SequencingPolicy(type = MetadataSequencingPolicy.class, parameters = "tenantId")
                @EventHandler(event = {
                        @QualifiedName(namespace = "com.acme", name = "OrderPlacedEvent"),
                        @QualifiedName(namespace = "com.acme.legacy", name = "OrderPlaced")
                })
                public void handle(OrderPlacedEvent event) {
                    callCount.incrementAndGet();
                }
            };
            var component = annotatedEventHandlingComponent(handler);
            var current = orderPlacedNamed("com.acme.OrderPlacedEvent", Map.of("tenantId", "tenant-1"));
            var legacy = orderPlacedNamed("com.acme.legacy.OrderPlaced", Map.of("tenantId", "tenant-2"));
            var currentContext = StubProcessingContext.forMessage(current);
            var legacyContext = StubProcessingContext.forMessage(legacy);

            // when
            var currentResult = component.handle(current, currentContext);
            var legacyResult = component.handle(legacy, legacyContext);

            // then
            assertSuccessfulStream(currentResult);
            assertSuccessfulStream(legacyResult);
            assertThat(callCount.get()).isEqualTo(2);
            assertThat(component.sequenceIdentifierFor(current, currentContext)).isEqualTo("tenant-1");
            assertThat(component.sequenceIdentifierFor(legacy, legacyContext)).isEqualTo("tenant-2");
        }

        @Test
        void emptyNamespaceDefaultsToNamespaceOfPayloadType() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(event = @QualifiedName(name = "OrderPlaced"))
                public void handle(OrderPlacedEvent event) {
                    // no-op
                }
            };

            // when
            var supportedEvents = namesSupportedBy(annotatedEventHandlingComponent(handler));

            // then
            assertThat(supportedEvents).containsExactly("com.acme.OrderPlaced");
        }

        @Test
        void emptyNamespaceDefaultsToNamespaceAnnotationOfPayloadType() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(event = @QualifiedName(name = "InvoiceSettled"))
                public void handle(InvoicePaid event) {
                    // no-op
                }
            };

            // when
            var supportedEvents = namesSupportedBy(annotatedEventHandlingComponent(handler));

            // then
            assertThat(supportedEvents).containsExactly("billing.InvoiceSettled");
        }

        @Test
        void emptyNameDefaultsToLocalNameOfPayloadType() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(event = @QualifiedName(namespace = "com.acme.legacy"))
                public void handle(OrderPlacedEvent event) {
                    // no-op
                }
            };

            // when
            var supportedEvents = namesSupportedBy(annotatedEventHandlingComponent(handler));

            // then
            assertThat(supportedEvents).containsExactly("com.acme.legacy.OrderPlacedEvent");
        }

        @Test
        void emptyNameDefaultsToPartOfPayloadTypeNameAfterItsLastDot() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(event = @QualifiedName(namespace = "legacy"))
                public void handle(VersionedOrderPlaced event) {
                    // no-op
                }
            };

            // when
            var supportedEvents = namesSupportedBy(annotatedEventHandlingComponent(handler));

            // then the payload type's name "orders.v2.OrderPlaced" has local name "OrderPlaced"
            assertThat(supportedEvents).containsExactly("legacy.OrderPlaced");
        }

        @Test
        void emptyNamespaceAndNameDefaultToPayloadTypeName() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(event = @QualifiedName())
                public void handle(OrderPlacedEvent event) {
                    // no-op
                }
            };

            // when
            var supportedEvents = namesSupportedBy(annotatedEventHandlingComponent(handler));

            // then
            assertThat(supportedEvents).containsExactly("com.acme.OrderPlacedEvent");
        }

        @Test
        void emptyPartsDefaultToNameOfDeclaredPayloadTypeAttribute() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(payloadType = OrderPlacedEvent.class, event = @QualifiedName(namespace = "com.acme.legacy"))
                public void handle(Object event) {
                    callCount.incrementAndGet();
                }
            };
            var component = annotatedEventHandlingComponent(handler);
            var legacy = orderPlacedNamed("com.acme.legacy.OrderPlacedEvent");

            // when
            var result = component.handle(legacy, StubProcessingContext.forMessage(legacy));

            // then
            assertThat(namesSupportedBy(component)).containsExactly("com.acme.legacy.OrderPlacedEvent");
            assertSuccessfulStream(result);
            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        void fullyDeclaredQualifiedNamesDoNotRequireResolvingThePayloadType() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(event = @QualifiedName(namespace = "com.acme.legacy", name = "OrderPlaced"))
                public void handle(OrderPlacedEvent event) {
                    // no-op
                }
            };

            // when
            var supportedEvents = namesSupportedBy(annotatedEventHandlingComponent(handler, UNRESOLVABLE));

            // then
            assertThat(supportedEvents).containsExactly("com.acme.legacy.OrderPlaced");
        }
    }

    @Nested
    @SuppressWarnings("removal")
    class DeprecatedEventNameAttribute {

        @Test
        void declaredEventNameReplacesPayloadTypeName() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(eventName = "com.acme.OrderPlaced")
                public void handle(OrderPlacedEvent event) {
                    callCount.incrementAndGet();
                }
            };
            var component = annotatedEventHandlingComponent(handler);
            var event = orderPlacedNamed("com.acme.OrderPlaced");

            // when
            var result = component.handle(event, StubProcessingContext.forMessage(event));

            // then
            assertThat(namesSupportedBy(component)).containsExactly("com.acme.OrderPlaced");
            assertSuccessfulStream(result);
            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        void eventWithPayloadTypeNameIsNotRoutedWhenEventNameIsDeclared() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(eventName = "com.acme.OrderPlaced")
                public void handle(OrderPlacedEvent event) {
                    callCount.incrementAndGet();
                }
            };
            var component = annotatedEventHandlingComponent(handler);
            // Named after the payload type, not after the declared event name
            var event = orderPlacedNamed("com.acme.OrderPlacedEvent");

            // when
            var result = component.handle(event, StubProcessingContext.forMessage(event));

            // then
            assertThat(result.error()).isPresent();
            assertThat(callCount.get()).isZero();
        }

        @Test
        void declaredEventNameDoesNotRequireResolvingThePayloadType() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(eventName = "com.acme.OrderPlaced")
                public void handle(OrderPlacedEvent event) {
                    // no-op
                }
            };

            // when
            var supportedEvents = namesSupportedBy(annotatedEventHandlingComponent(handler, UNRESOLVABLE));

            // then
            assertThat(supportedEvents).containsExactly("com.acme.OrderPlaced");
        }

        @Test
        void eventNameAndEventAttributesAreCombined() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(
                        eventName = "com.acme.OrderPlaced",
                        event = @QualifiedName(namespace = "com.acme.legacy", name = "OrderPlaced")
                )
                public void handle(OrderPlacedEvent event) {
                    callCount.incrementAndGet();
                }
            };
            var component = annotatedEventHandlingComponent(handler);
            var current = orderPlacedNamed("com.acme.OrderPlaced");
            var legacy = orderPlacedNamed("com.acme.legacy.OrderPlaced");

            // when
            var currentResult = component.handle(current, StubProcessingContext.forMessage(current));
            var legacyResult = component.handle(legacy, StubProcessingContext.forMessage(legacy));

            // then
            assertThat(namesSupportedBy(component)).containsExactlyInAnyOrder(
                    "com.acme.OrderPlaced",
                    "com.acme.legacy.OrderPlaced"
            );
            assertSuccessfulStream(currentResult);
            assertSuccessfulStream(legacyResult);
            assertThat(callCount.get()).isEqualTo(2);
        }

        @Test
        void sameNameDeclaredThroughBothAttributesInvokesHandlerOnce() {
            // given
            var handler = new Object() {
                @SuppressWarnings("unused")
                @EventHandler(
                        eventName = "com.acme.OrderPlacedEvent",
                        event = @QualifiedName(namespace = "com.acme", name = "OrderPlacedEvent")
                )
                public void handle(OrderPlacedEvent event) {
                    callCount.incrementAndGet();
                }
            };
            var component = annotatedEventHandlingComponent(handler);
            var event = orderPlacedNamed("com.acme.OrderPlacedEvent");

            // when
            var result = component.handle(event, StubProcessingContext.forMessage(event));

            // then
            assertThat(namesSupportedBy(component)).containsExactly("com.acme.OrderPlacedEvent");
            assertSuccessfulStream(result);
            assertThat(callCount.get()).isEqualTo(1);
        }
    }

    @Test
    void noDeclaredNamesFallsBackToPayloadTypeName() {
        // given
        var handler = new Object() {
            @SuppressWarnings("unused")
            @EventHandler
            public void handle(OrderPlacedEvent event) {
                // no-op
            }
        };

        // when
        var supportedEvents = namesSupportedBy(annotatedEventHandlingComponent(handler));

        // then
        assertThat(supportedEvents).containsExactly("com.acme.OrderPlacedEvent");
    }

    private static EventMessage orderPlacedNamed(String qualifiedName) {
        return orderPlacedNamed(qualifiedName, Map.of());
    }

    private static EventMessage orderPlacedNamed(String qualifiedName, Map<String, String> metadata) {
        return EventTestUtils.asEventMessage(
                new GenericMessage(new MessageType(qualifiedName), new OrderPlacedEvent("order-1"), metadata)
        );
    }

    private static void assertSuccessfulStream(MessageStream.Empty<Message> result) {
        assertThat(result.error()).isEmpty();
    }

    private static List<String> namesSupportedBy(EventHandlingComponent component) {
        return component.supportedEvents().stream().map(name -> name.name()).toList();
    }

    private static AnnotatedEventHandlingComponent<?> annotatedEventHandlingComponent(Object eventHandler) {
        return annotatedEventHandlingComponent(eventHandler, new AnnotationMessageTypeResolver());
    }

    private static AnnotatedEventHandlingComponent<?> annotatedEventHandlingComponent(
            Object eventHandler,
            MessageTypeResolver messageTypeResolver
    ) {
        return new AnnotatedEventHandlingComponent<>(
                eventHandler,
                ClasspathParameterResolverFactory.forClass(eventHandler.getClass()),
                ClasspathHandlerDefinition.forClass(eventHandler.getClass()),
                messageTypeResolver,
                new DelegatingEventConverter(PassThroughConverter.INSTANCE)
        );
    }
}

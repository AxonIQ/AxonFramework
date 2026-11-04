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

import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.QualifiedName;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.annotation.Event;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.modelling.EntityEvolvingComponent;
import org.axonframework.modelling.annotation.AnnotationBasedEntityEvolvingComponent;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating that the {@link EventSourcingHandler#event()} attribute defines the names of the events an
 * {@link EventSourcingHandler} annotated method sources.
 *
 * @author Allard Buijze
 */
class EventSourcingHandlerEventNamesTest {

    @Event(namespace = "com.acme")
    private record OrderPlacedEvent(String id) {

    }

    @SuppressWarnings("unused")
    private static class QualifiedNameEntity {

        int sourcedCount = 0;

        @EventSourcingHandler(event = {
                @QualifiedName(namespace = "com.acme", name = "OrderPlacedEvent"),
                @QualifiedName(namespace = "com.acme.legacy", name = "OrderPlaced")
        })
        void on(OrderPlacedEvent event) {
            sourcedCount++;
        }
    }

    @SuppressWarnings("unused")
    private static class PartialQualifiedNameEntity {

        @EventSourcingHandler(event = @QualifiedName(namespace = "com.acme.legacy"))
        void on(OrderPlacedEvent event) {
            // no-op
        }
    }

    @SuppressWarnings("unused")
    private static class UndeclaredNameEntity {

        @EventSourcingHandler
        void on(OrderPlacedEvent event) {
            // no-op
        }
    }

    @Nested
    class EventAttribute {

        private final AnnotationBasedEntityEvolvingComponent<QualifiedNameEntity> evolver =
                evolverFor(QualifiedNameEntity.class);

        @Test
        void supportedEventsContainsAllQualifiedNames() {
            // when
            var supportedEvents = namesSupportedBy(evolver);

            // then
            assertThat(supportedEvents).containsExactlyInAnyOrder("com.acme.OrderPlacedEvent",
                                                                  "com.acme.legacy.OrderPlaced");
        }

        @Test
        void evolveSourcesEachQualifiedName() {
            // given
            var entity = new QualifiedNameEntity();
            var current = orderPlacedNamed("com.acme.OrderPlacedEvent");
            var legacy = orderPlacedNamed("com.acme.legacy.OrderPlaced");

            // when
            evolver.evolve(entity, current, StubProcessingContext.forMessage(current));
            evolver.evolve(entity, legacy, StubProcessingContext.forMessage(legacy));

            // then
            assertThat(entity.sourcedCount).isEqualTo(2);
        }

        @Test
        void emptyNameDefaultsToLocalNameOfPayloadType() {
            // when
            var supportedEvents = namesSupportedBy(evolverFor(PartialQualifiedNameEntity.class));

            // then
            assertThat(supportedEvents).containsExactly("com.acme.legacy.OrderPlacedEvent");
        }
    }

    @Test
    void undeclaredNameFallsBackToPayloadTypeName() {
        // when
        var supportedEvents = namesSupportedBy(evolverFor(UndeclaredNameEntity.class));

        // then
        assertThat(supportedEvents).containsExactly("com.acme.OrderPlacedEvent");
    }

    private static EventMessage orderPlacedNamed(String qualifiedName) {
        return EventTestUtils.asEventMessage(
                new GenericMessage(new MessageType(qualifiedName), new OrderPlacedEvent("order-1"))
        );
    }

    private static List<String> namesSupportedBy(EntityEvolvingComponent<?> evolver) {
        return evolver.supportedEvents().stream().map(name -> name.name()).toList();
    }

    private static <E> AnnotationBasedEntityEvolvingComponent<E> evolverFor(Class<E> entityType) {
        return new AnnotationBasedEntityEvolvingComponent<>(
                entityType,
                new DelegatingEventConverter(PassThroughConverter.INSTANCE),
                new AnnotationMessageTypeResolver(),
                ClasspathParameterResolverFactory.forClass(entityType),
                ClasspathHandlerDefinition.forClass(entityType)
        );
    }
}

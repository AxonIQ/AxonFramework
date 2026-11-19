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
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that an entity's {@code @EventHandler} is registered under its explicit {@link EventHandler#eventName()},
 * rather than under the {@link QualifiedName} derived from the handler's payload type. Root and single-value child
 * entities are pinned to keep applying such events, and two handlers sharing one explicit name with different payload
 * types are rejected when the metamodel is built. Routing to {@code List} and {@code Map} child entities is covered end
 * to end by {@code EntityMemberMessageRoutingTest} in the test module.
 *
 * @author Mateusz Nowak
 */
class AnnotatedEntityMetamodelEventNameRegistrationTest {

    private static final String EXPLICIT_EVENT_NAME = "custom.childRenamed";

    @Nested
    class SingleChildEventRouting extends AbstractAnnotatedEntityMetamodelTest<ParentWithSingleChild> {

        @Override
        protected AnnotatedEntityMetamodel<ParentWithSingleChild> getMetamodel() {
            return AnnotatedEntityMetamodel.forConcreteType(
                    ParentWithSingleChild.class,
                    parameterResolverFactory,
                    handlerDefinition,
                    messageTypeResolver,
                    messageConverter,
                    eventConverter
            );
        }

        @Test
        void eventWithExplicitEventNameIsAppliedToTheSingleChild() {
            // given
            Child child = new Child("child-1");
            ParentWithSingleChild parent = new ParentWithSingleChild(child);
            EventMessage event = namedEvent(new NamedChildRenamed("child-1", "new-name"));

            // when
            metamodel.evolve(parent, event, StubProcessingContext.forMessage(event));

            // then
            assertThat(child.name).isEqualTo("new-name");
        }
    }

    @Nested
    class RootEventRouting extends AbstractAnnotatedEntityMetamodelTest<Child> {

        @Override
        protected AnnotatedEntityMetamodel<Child> getMetamodel() {
            return AnnotatedEntityMetamodel.forConcreteType(
                    Child.class,
                    parameterResolverFactory,
                    handlerDefinition,
                    messageTypeResolver,
                    messageConverter,
                    eventConverter
            );
        }

        @Test
        void eventWithExplicitEventNameIsAppliedToTheRootEntity() {
            // given
            Child root = new Child("child-1");
            EventMessage event = namedEvent(new NamedChildRenamed("child-1", "new-name"));

            // when
            metamodel.evolve(root, event, StubProcessingContext.forMessage(event));

            // then
            assertThat(root.name).isEqualTo("new-name");
        }
    }

    @Nested
    class ConflictingExplicitEventNames {

        /**
         * One event name maps to one payload representation per entity, as it does for {@code commandName}. Before
         * the explicit name was registered, both handlers were keyed by their own payload type and each event named
         * {@code custom.childRenamed} was converted into, and applied through, both of them.
         */
        @Test
        void twoEventHandlersWithTheSameExplicitEventNameAndDifferentPayloadTypesAreRejected() {
            // when / then
            assertThatThrownBy(() -> AnnotatedEntityMetamodel.forConcreteType(
                    EntityWithConflictingEventNames.class,
                    ClasspathParameterResolverFactory.forClass(getClass()),
                    ClasspathHandlerDefinition.forClass(getClass()),
                    new ClassBasedMessageTypeResolver(),
                    new DelegatingMessageConverter(new JacksonConverter()),
                    new DelegatingEventConverter(new JacksonConverter())
            )).isInstanceOf(AxonConfigurationException.class)
              .hasMessageContaining(EXPLICIT_EVENT_NAME);
        }
    }

    private static EventMessage namedEvent(NamedChildRenamed payload) {
        return new GenericEventMessage(new MessageType(EXPLICIT_EVENT_NAME), payload);
    }

    public record ChildRenamed(String childId, String name) {

    }

    public record NamedChildRenamed(String childId, String name) {

    }

    public static class Child {

        private final String childId;
        private String name = "initial";

        public Child(String childId) {
            this.childId = childId;
        }

        public String childId() {
            return childId;
        }

        @EventHandler
        void on(ChildRenamed event) {
            this.name = event.name();
        }

        @EventHandler(eventName = EXPLICIT_EVENT_NAME)
        void on(NamedChildRenamed event) {
            this.name = event.name();
        }
    }

    public static class EntityWithConflictingEventNames {

        @EventHandler(eventName = EXPLICIT_EVENT_NAME)
        void on(ChildRenamed event) {
        }

        @EventHandler(eventName = EXPLICIT_EVENT_NAME)
        void on(NamedChildRenamed event) {
        }
    }

    public static class ParentWithSingleChild {

        @EntityMember
        private final Child child;

        public ParentWithSingleChild(Child child) {
            this.child = child;
        }
    }
}

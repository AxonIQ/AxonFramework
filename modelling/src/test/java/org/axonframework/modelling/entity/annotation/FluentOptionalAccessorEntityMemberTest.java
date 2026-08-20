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

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.junit.jupiter.api.*;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that an {@link EntityMember}-annotated field whose owning class also declares a same-named fluent accessor
 * returning {@code Optional<Child>} (rather than {@code Child} itself) is still evolved correctly by
 * {@link AnnotatedEntityMetamodel}. Such an accessor is name-compatible but type-incompatible with the field, and must
 * not be mistaken for a usable getter/setter by
 * {@link org.axonframework.modelling.entity.child.FieldChildEntityFieldDefinition}.
 *
 * @author Steven van Beelen
 */
class FluentOptionalAccessorEntityMemberTest
        extends AbstractAnnotatedEntityMetamodelTest<FluentOptionalAccessorEntityMemberTest.FluentOwner> {

    @Override
    protected AnnotatedEntityMetamodel<FluentOwner> getMetamodel() {
        return AnnotatedEntityMetamodel.forConcreteType(FluentOwner.class,
                                                        parameterResolverFactory,
                                                        handlerDefinition,
                                                        messageTypeResolver,
                                                        messageConverter,
                                                        eventConverter);
    }

    @Test
    void eventSourcingHandlerCreatesChildDespiteIncompatibleFluentOptionalAccessor() {
        entityState = new FluentOwner();

        dispatchInstanceCommand(new CreateChild("new-name"));

        assertThat(entityState.child()).isPresent();
        assertThat(entityState.child().get().getName()).isEqualTo("new-name");
    }

    static class FluentOwner {

        @EntityMember
        private FluentChild child;

        @CommandHandler
        public void handle(CreateChild command, EventAppender appender) {
            appender.append(new ChildCreated(command.name()));
        }

        @EventHandler
        public void on(ChildCreated event) {
            this.child = new FluentChild(event.name());
        }

        // Fluent-style accessor: same name as the field, no "get" prefix, wraps the value in Optional.
        // Not annotated with @EntityMember, and not type-compatible with the field.
        public Optional<FluentChild> child() {
            return Optional.ofNullable(child);
        }
    }

    static class FluentChild {

        private final String name;

        FluentChild(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    record CreateChild(String name) {

    }

    record ChildCreated(String name) {

    }
}

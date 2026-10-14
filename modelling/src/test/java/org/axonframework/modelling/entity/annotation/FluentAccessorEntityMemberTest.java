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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that an {@link EntityMember}-annotated field is still discovered by {@link AnnotatedEntityMetamodel} when the
 * owning class also declares a fluent-style accessor method (same name as the field, no {@code get}/{@code is} prefix)
 * that is not itself annotated with {@link EntityMember}.
 * <p>
 * Fluent accessors can be added manually by a user, but may enter unexpectedly when they use Lombok's
 * {@code @Accessors(fluent = true)} annotation, which generates exactly such accessors.
 *
 * @author Steven van Beelen
 */
class FluentAccessorEntityMemberTest
        extends AbstractAnnotatedEntityMetamodelTest<FluentAccessorEntityMemberTest.FluentOwner> {

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
    void eventHandlerOnEntityMemberIsInvokedDespiteCollidingFluentAccessor() {
        entityState = new FluentOwner();

        dispatchInstanceCommand(new RenameChild("new-name"));

        assertThat(entityState.child().getName()).isEqualTo("new-name");
    }

    static class FluentOwner {

        @EntityMember
        private final FluentChild child = new FluentChild();

        // Fluent-style accessor: same name as the field, no "get" prefix, not annotated with @EntityMember.
        public FluentChild child() {
            return child;
        }
    }

    static class FluentChild {

        private String name = "initial";

        @CommandHandler
        public void handle(RenameChild command, EventAppender appender) {
            appender.append(new ChildRenamed(command.name()));
        }

        @EventHandler
        public void on(ChildRenamed event) {
            this.name = event.name();
        }

        public String getName() {
            return name;
        }
    }

    record RenameChild(String name) {

    }

    record ChildRenamed(String name) {

    }
}

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

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.NoHandlerForCommandException;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that an entity's {@code @CommandHandler} is registered under its explicit {@link CommandHandler#commandName()},
 * rather than under the {@link QualifiedName} derived from the handler's payload type.
 *
 * @author Steven van Beelen
 */
class AnnotatedEntityMetamodelCommandNameRegistrationTest
        extends
        AbstractAnnotatedEntityMetamodelTest<AnnotatedEntityMetamodelCommandNameRegistrationTest.NamedCommandEntity> {

    private static final String EXPLICIT_COMMAND_NAME = "custom.commandName";

    @Override
    protected AnnotatedEntityMetamodel<NamedCommandEntity> getMetamodel() {
        return AnnotatedEntityMetamodel.forConcreteType(
                NamedCommandEntity.class,
                parameterResolverFactory,
                handlerDefinition,
                messageTypeResolver,
                messageConverter,
                eventConverter
        );
    }

    @Test
    void handleInstanceForCommandNameBasedCommandHandlesCommandAsExpected() {
        // given
        entityState = new NamedCommandEntity();
        CommandMessage command =
                new GenericCommandMessage(new MessageType(EXPLICIT_COMMAND_NAME), new Rename("new-name"));

        // when
        metamodel.handleInstance(command, entityState, StubProcessingContext.forMessage(command))
                 .first()
                 .asCompletableFuture()
                 .join();

        // then
        assertThat(entityState.name).isEqualTo("new-name");
    }

    @Test
    void handleInstanceForPayloadBasedCommandNameThrowsNoHandlerForCommandExceptionWhenRegisteredThroughCommandName() {
        // given
        entityState = new NamedCommandEntity();
        CommandMessage command = new GenericCommandMessage(new MessageType(Rename.class), new Rename("new-name"));

        // when / then
        assertThatThrownBy(
                () -> metamodel.handleInstance(command, entityState, StubProcessingContext.forMessage(command))
                               .first()
                               .asCompletableFuture()
                               .join()
        ).hasCauseInstanceOf(NoHandlerForCommandException.class);
    }

    @Test
    void handleCreateForUnregisteredCommandNameReturnsFailedStreamWithNoHandlerForCommandException() {
        // given
        CommandMessage command = new GenericCommandMessage(new MessageType(Rename.class), new Rename("new-name"));

        // when / then
        assertThatThrownBy(
                () -> metamodel.handleCreate(command, StubProcessingContext.forMessage(command))
                               .first()
                               .asCompletableFuture()
                               .join()
        ).hasCauseInstanceOf(NoHandlerForCommandException.class);
    }

    public record Rename(String name) {

    }

    public static class NamedCommandEntity {

        private String name;

        @CommandHandler(commandName = EXPLICIT_COMMAND_NAME)
        void handle(Rename command) {
            this.name = command.name();
        }
    }
}

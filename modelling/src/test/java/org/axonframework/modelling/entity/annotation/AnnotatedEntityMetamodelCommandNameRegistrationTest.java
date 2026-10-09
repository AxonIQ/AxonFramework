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
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that an entity's {@code @CommandHandler} is registered under its explicit {@link CommandHandler#commandName()},
 * rather than under the {@link QualifiedName} derived from the handler's payload type. Each nested class exercises a
 * different code path that consults the registered name: plain instance/creational routing, polymorphic creational
 * handler de-duplication, child-entity routing, and a handler without a business payload parameter.
 *
 * @author Steven van Beelen
 */
class AnnotatedEntityMetamodelCommandNameRegistrationTest {

    private static final String EXPLICIT_COMMAND_NAME = "custom.commandName";

    @Nested
    class InstanceCommandRouting extends AbstractAnnotatedEntityMetamodelTest<NamedCommandEntity> {

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
        void explicitCommandNameIsPresentInSupportedCommands() {
            // The cheap assertion pinning what actually gets subscribed to the bus.
            assertThat(metamodel.supportedCommands()).contains(new QualifiedName(EXPLICIT_COMMAND_NAME));
        }

        @Test
        void handleInstanceConvertsASerializedJsonPayloadToTheExpectedRepresentation() {
            // given
            entityState = new NamedCommandEntity();
            byte[] jsonPayload = "{\"name\":\"new-name\"}".getBytes(StandardCharsets.UTF_8);
            CommandMessage command = new GenericCommandMessage(new MessageType(EXPLICIT_COMMAND_NAME), jsonPayload);

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
    }

    @Nested
    class PolymorphicCreationalCommandRouting extends AbstractAnnotatedEntityMetamodelTest<PolymorphicNamedEntity> {

        @Override
        protected AnnotatedEntityMetamodel<PolymorphicNamedEntity> getMetamodel() {
            return AnnotatedEntityMetamodel.forPolymorphicType(
                    PolymorphicNamedEntity.class,
                    Set.of(ConcretePolymorphicNamedEntity.class),
                    parameterResolverFactory,
                    handlerDefinition,
                    messageTypeResolver,
                    messageConverter,
                    eventConverter
            );
        }

        /**
         * Builds without clashing on the inherited creational handler, and the creational command is still routed to
         * it: {@code commandsToSkip}, used to avoid re-registering a concrete type's inherited creational handler, must
         * key off the same explicit {@code commandName} the handler is otherwise registered under.
         */
        @Test
        void creationalCommandNameHandlerOnAbstractSuperTypeIsHandled() {
            // given
            entityState = null;
            CommandMessage command =
                    new GenericCommandMessage(new MessageType(EXPLICIT_COMMAND_NAME), new CreateNamed("new-entity"));

            // when
            metamodel.handleCreate(command, StubProcessingContext.forMessage(command))
                     .first()
                     .asCompletableFuture()
                     .join();

            // then
            assertThat(publishedEvents).containsExactly(new NamedEntityCreated("new-entity"));
        }
    }

    @Nested
    class ChildEntityCommandRouting extends AbstractAnnotatedEntityMetamodelTest<ParentWithNamedChild> {

        @Override
        protected AnnotatedEntityMetamodel<ParentWithNamedChild> getMetamodel() {
            return AnnotatedEntityMetamodel.forConcreteType(
                    ParentWithNamedChild.class,
                    parameterResolverFactory,
                    handlerDefinition,
                    messageTypeResolver,
                    messageConverter,
                    eventConverter
            );
        }

        /**
         * {@code AnnotatedEntityModelRoutingKeyMatcher} resolves the routing payload's representation through
         * {@code getExpectedRepresentation}, keyed by the same explicit {@code commandName} the child's handler is
         * registered under. Without the fix, that lookup misses and the child is silently never selected.
         */
        @Test
        void childEntityCommandHandlerIsRoutedByItsExplicitCommandName() {
            // given
            ChildWithNamedCommand child = new ChildWithNamedCommand("child-1");
            entityState = new ParentWithNamedChild(new ArrayList<>(List.of(child)));
            CommandMessage command = new GenericCommandMessage(
                    new MessageType(EXPLICIT_COMMAND_NAME), new RenameChild("child-1", "new-value")
            );

            // when
            metamodel.handleInstance(command, entityState, StubProcessingContext.forMessage(command))
                     .first()
                     .asCompletableFuture()
                     .join();

            // then
            assertThat(child.value()).isEqualTo("new-value");
        }
    }

    @Nested
    class MessageOnlyCommandRouting extends AbstractAnnotatedEntityMetamodelTest<MessageOnlyEntity> {

        @Override
        protected AnnotatedEntityMetamodel<MessageOnlyEntity> getMetamodel() {
            return AnnotatedEntityMetamodel.forConcreteType(
                    MessageOnlyEntity.class,
                    parameterResolverFactory,
                    handlerDefinition,
                    messageTypeResolver,
                    messageConverter,
                    eventConverter
            );
        }

        /**
         * A handler with no business-payload parameter resolves {@code payloadType()} to {@link Object}. Before the
         * fix, such a handler always registered as {@code java.lang.Object}, regardless of {@code commandName}. This is
         * the shape a migrated Axon Framework 4 deadline handler without a payload takes.
         */
        @Test
        void handlerWithoutABusinessPayloadIsRoutedByItsExplicitCommandName() {
            // given
            entityState = new MessageOnlyEntity();
            CommandMessage command = new GenericCommandMessage(new MessageType(EXPLICIT_COMMAND_NAME), "irrelevant");

            // when
            metamodel.handleInstance(command, entityState, StubProcessingContext.forMessage(command))
                     .first()
                     .asCompletableFuture()
                     .join();

            // then
            assertThat(entityState.lastHandledCommandIdentifier).isEqualTo(command.identifier());
        }
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

    public record CreateNamed(String name) {

    }

    public record NamedEntityCreated(String name) {

    }

    public abstract static class PolymorphicNamedEntity {

        @CommandHandler(commandName = EXPLICIT_COMMAND_NAME)
        public static String handle(CreateNamed command, EventAppender appender) {
            appender.append(new NamedEntityCreated(command.name()));
            return command.name();
        }
    }

    public static class ConcretePolymorphicNamedEntity extends PolymorphicNamedEntity {

    }

    public record RenameChild(String childId, String value) {

    }

    public static class ParentWithNamedChild {

        @EntityMember(routingKey = "childId")
        private final List<ChildWithNamedCommand> children;

        public ParentWithNamedChild(List<ChildWithNamedCommand> children) {
            this.children = children;
        }
    }

    public static class ChildWithNamedCommand {

        private final String childId;
        private String value;

        public ChildWithNamedCommand(String childId) {
            this.childId = childId;
        }

        public String childId() {
            return childId;
        }

        public String value() {
            return value;
        }

        @CommandHandler(commandName = EXPLICIT_COMMAND_NAME)
        void handle(RenameChild command) {
            this.value = command.value();
        }
    }

    public static class MessageOnlyEntity {

        private String lastHandledCommandIdentifier;

        @CommandHandler(commandName = EXPLICIT_COMMAND_NAME)
        void handle(CommandMessage command) {
            this.lastHandledCommandIdentifier = command.identifier();
        }
    }
}

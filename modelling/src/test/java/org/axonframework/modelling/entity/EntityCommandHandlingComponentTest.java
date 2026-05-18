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

package org.axonframework.modelling.entity;

import org.axonframework.common.infra.MockComponentDescriptor;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.commandhandling.NoHandlerForCommandException;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.modelling.EntityIdResolutionException;
import org.axonframework.modelling.EntityIdResolver;
import org.axonframework.modelling.repository.ManagedEntity;
import org.axonframework.modelling.repository.Repository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockito.*;
import org.mockito.junit.jupiter.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.axonframework.messaging.core.MessageStreamTestUtils.assertCompletedExceptionally;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EntityCommandHandlingComponentTest {

    private final StubProcessingContext context = new StubProcessingContext();
    private final MessageType creationalMessageType = new MessageType("CreationalCommand");
    private final CommandMessage creationalCommandMessage =
            new GenericCommandMessage(creationalMessageType, "command");
    private final MessageType instanceMessageType = new MessageType("InstanceCommand");
    private final CommandMessage instanceCommandMessage =
            new GenericCommandMessage(instanceMessageType, "command");
    private final MessageType mixedMessageType = new MessageType("MixedCommand");
    private final CommandMessage mixedCommandMessage = new GenericCommandMessage(mixedMessageType, "command");
    private final MessageType missingMessageType = new MessageType("MissingCommand");
    private final CommandMessage missingCommandMessage =
            new GenericCommandMessage(missingMessageType, "command");
    private final String entityId = "myEntityId456";

    @Mock
    private Repository.LifecycleManagement<String, TestEntity> repository;

    // We create a SimpleEntityModel, and rely on its API. If not, we basically have to mock every piece of behavior.
    @Spy
    private EntityMetamodel<TestEntity> metamodel = EntityMetamodel
            .forEntityType(TestEntity.class)
            .creationalCommandHandler(creationalMessageType.qualifiedName(),
                                      ((command, c) -> resultMessage("creational")))
            .creationalCommandHandler(mixedMessageType.qualifiedName(),
                                      ((command, c) -> resultMessage("creational-mixed")))
            .instanceCommandHandler(instanceMessageType.qualifiedName(),
                                    ((command, entity, c) -> resultMessage("instance")))
            .instanceCommandHandler(mixedMessageType.qualifiedName(),
                                    ((command, entity, c) -> resultMessage("instance-mixed")))
            .build();

    @Mock
    private EntityIdResolver<String> idResolver;

    @InjectMocks
    private EntityCommandHandlingComponent<String, TestEntity> testComponent;

    @BeforeEach
    void setUp() throws EntityIdResolutionException {
        lenient().when(idResolver.resolve(any(), any())).thenReturn(entityId);
    }

    @Test
    void returnsSupportedCommandFromModel() {
        Set<QualifiedName> result = testComponent.supportedCommands();
        assertEquals(Set.of(
                creationalMessageType.qualifiedName(),
                instanceMessageType.qualifiedName(),
                mixedMessageType.qualifiedName()
        ), result);
    }

    @Test
    void resultsInNoHandlerExceptionWhenIsUnknownCommand() {
        MessageStream.Single<CommandResultMessage> componentResult = testComponent
                .handle(missingCommandMessage, context);

        assertCompletedExceptionally(componentResult,
                                     NoHandlerForCommandException.class,
                                     "No handler for command [MissingCommand]");
    }

    @Nested
    class OnlyCreationalCommandHandler {

        @Test
        void executesCreationalCommandHandlerAfterLoadGivesNullResult() {
            setupLoadEntity(null);
            CommandResultMessage resultMessage = testComponent.handle(creationalCommandMessage, context)
                                                              .first()
                                                              .asCompletableFuture()
                                                              .join()
                                                              .message();

            verify(metamodel).handleCreate(eq(creationalCommandMessage), any());
            assertEquals("creational", resultMessage.payload());
        }

        @Test
        void resultsInExceptionWhenLoadReturnsNonNullEntity() {
            setupLoadEntity(new TestEntity());

            MessageStream.Single<CommandResultMessage> result =
                    testComponent.handle(creationalCommandMessage, context);

            verify(metamodel, times(0)).handleCreate(eq(creationalCommandMessage), any());
            assertCompletedExceptionally(result, EntityAlreadyExistsForCreationalCommandHandlerException.class);
        }


        @Test
        void failureToLoadEntityWillResultInFailedMessageStream() {
            when(repository.load(eq(entityId), any()))
                    .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Failed to load entity")));

            MessageStream.Single<CommandResultMessage> componentResult =
                    testComponent.handle(creationalCommandMessage, context);

            var exception = assertThrows(RuntimeException.class, () -> componentResult.asCompletableFuture().join());
            assertEquals("Failed to load entity", exception.getCause().getMessage());
        }
    }

    @Nested
    class OnlyInstanceCommandHandler {

        @Test
        void willThrowExceptionIfEntityDoesNotExist() {
            setupLoadOrCreateEntity(null);

            MessageStream.Single<CommandResultMessage> componentResult =
                    testComponent.handle(instanceCommandMessage, context);

            assertCompletedExceptionally(componentResult, EntityMissingForInstanceCommandHandlerException.class);
        }

        @Test
        void willCallInstanceCommandHandlerWhenEntityExists() {
            TestEntity testEntity = new TestEntity();
            setupLoadOrCreateEntity(testEntity);

            MessageStream.Single<CommandResultMessage> componentResult =
                    testComponent.handle(instanceCommandMessage, context);

            verify(metamodel).handleInstance(eq(instanceCommandMessage), eq(testEntity), any());
            assertEquals("instance", componentResult.asCompletableFuture().join().message().payload());
        }


        @Test
        void failureToLoadOrCreateEntityWillResultInFailedMessageStream() {
            when(repository.loadOrCreate(eq(entityId), any()))
                    .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Failed to load entity")));

            MessageStream.Single<CommandResultMessage> componentResult =
                    testComponent.handle(instanceCommandMessage, context);

            var exception = assertThrows(RuntimeException.class, () -> componentResult.asCompletableFuture().join());
            assertEquals("Failed to load entity", exception.getCause().getMessage());
        }
    }

    @Nested
    class BothCreateAndInstance {


        @Test
        void willInvokeCreationalCommandHandlerWhenRepositoryReturnsNull() {
            setupLoadEntity(null);

            MessageStream.Single<CommandResultMessage> componentResult =
                    testComponent.handle(mixedCommandMessage, context);
            verify(metamodel).handleCreate(mixedCommandMessage, context);
            assertEquals("creational-mixed", componentResult.first().asCompletableFuture().join().message().payload());
        }

        @Test
        void willInvokeInstanceCommandHandlerWhenRepositoryReturnsEntity() {
            TestEntity entity = new TestEntity();
            setupLoadEntity(entity);

            MessageStream.Single<CommandResultMessage> componentResult =
                    testComponent.handle(mixedCommandMessage, context);

            verify(metamodel).handleInstance(mixedCommandMessage, entity, context);
            assertEquals("instance-mixed", componentResult.asCompletableFuture().join().message().payload());
        }

        @Test
        void failureToLoadEntityWillResultInFailedMessageStream() {
            when(repository.load(eq(entityId), any()))
                    .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Failed to load entity")));

            MessageStream.Single<CommandResultMessage> componentResult =
                    testComponent.handle(mixedCommandMessage, context);

            var exception = assertThrows(RuntimeException.class, () -> componentResult.asCompletableFuture().join());
            assertEquals("Failed to load entity", exception.getCause().getMessage());
        }
    }

    @Test
    void failureToResolveIdWillResultInFailedMessageStream() throws EntityIdResolutionException {
        when(idResolver.resolve(eq(creationalCommandMessage), any()))
                .thenThrow(new RuntimeException("Failed to resolve ID"));

        MessageStream.Single<? extends CommandResultMessage> componentResult =
                testComponent.handle(creationalCommandMessage, context);

        var exception = assertThrows(RuntimeException.class, () -> componentResult.asCompletableFuture().join());
        assertEquals("Failed to resolve ID", exception.getCause().getMessage());
    }

    /**
     * These tests validate the {@link EntityCommandHandlingComponent} its assumption that if the
     * {@link EntityIdResolver} throws an {@link EntityIdResolutionException}, that we may be dealing with a command
     * that does not have an entity identifier <b>because</b> it will create a new entity.
     * <p>
     * When this assumption is correct, we should be able to successfully invoke a
     * {@link EntityMetamodel#handleCreate(CommandMessage, ProcessingContext) creational command handler}. When this
     * assumption is incorrect, signaled with a {@link NoHandlerForCommandException}, we should fall back to the
     * original {@link EntityIdResolutionException}.
     */
    @Nested
    class EntityIdResolutionFallback {

        @Test
        void whenEntityIdResolverThrowsThenCreationalCommandHandlerIsInvokedSuccessfully() throws EntityIdResolutionException {
            when(idResolver.resolve(eq(creationalCommandMessage), any()))
                    .thenThrow(new EntityIdResolutionException(String.class, List.of()));

            MessageStream.Single<CommandResultMessage> componentResult =
                    testComponent.handle(creationalCommandMessage, context);

            verify(metamodel).handleCreate(eq(creationalCommandMessage), any());
            assertEquals("creational", componentResult.asCompletableFuture().join().message().payload());
        }

        @Test
        void whenEntityIdResolverThrowsAndEntityMetaModelThrowsReturnOriginalEntityIdResolutionException() throws EntityIdResolutionException {
            when(idResolver.resolve(eq(missingCommandMessage), any()))
                    .thenThrow(new EntityIdResolutionException(String.class, List.of()));

            MessageStream.Single<CommandResultMessage> componentResult =
                    testComponent.handle(missingCommandMessage, context);

            assertCompletedExceptionally(componentResult, EntityIdResolutionException.class, "String");
        }
    }

    @Test
    void correctlyDescribesComponent() {
        MockComponentDescriptor descriptor = new MockComponentDescriptor();
        testComponent.describeTo(descriptor);

        assertEquals(repository, descriptor.getProperty("repository"));
        assertEquals(metamodel, descriptor.getProperty("metamodel"));
        assertEquals(idResolver, descriptor.getProperty("idResolver"));
    }


    private static class TestEntity {
        // Test entity class for generic type resolution
    }

    private void setupLoadOrCreateEntity(TestEntity entity) {
        //noinspection unchecked
        ManagedEntity<String, TestEntity> managedEntity = mock(ManagedEntity.class);
        when(managedEntity.entity()).thenReturn(entity);
        when(repository.loadOrCreate(eq(entityId), any())).thenReturn(CompletableFuture.completedFuture(managedEntity));
    }

    private void setupLoadEntity(TestEntity entity) {
        //noinspection unchecked
        ManagedEntity<String, TestEntity> managedEntity = mock(ManagedEntity.class);
        when(managedEntity.entity()).thenReturn(entity);
        when(repository.load(eq(entityId), any())).thenReturn(CompletableFuture.completedFuture(managedEntity));
    }

    private static MessageStream.Single<CommandResultMessage> resultMessage(String creational) {
        return MessageStream.just(new GenericCommandResultMessage(new MessageType(String.class), creational));
    }
}
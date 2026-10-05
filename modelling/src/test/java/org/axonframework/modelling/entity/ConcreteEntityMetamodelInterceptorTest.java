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

import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageStreamTestUtils;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.modelling.entity.child.ChildEntityFieldDefinition;
import org.axonframework.modelling.entity.child.SingleEntityChildMetamodel;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link EntityCommandHandlerInterceptor} support as provided in the
 * {@link ConcreteEntityMetamodel}.
 *
 * @author Steven van Beelen
 */
class ConcreteEntityMetamodelInterceptorTest {

    private static final QualifiedName INSTANCE_COMMAND = new QualifiedName("InstanceCommand");
    private static final QualifiedName CREATIONAL_COMMAND = new QualifiedName("CreationalCommand");
    private static final QualifiedName CHILD_COMMAND = new QualifiedName("ChildCommand");

    private final TestEntity entity = new TestEntity();
    private final ProcessingContext context = new StubProcessingContext();

    @SuppressWarnings("unchecked")
    private final EntityCommandHandler<TestEntity> instanceHandler = mock(EntityCommandHandler.class);
    private final CommandHandler creationalHandler = mock(CommandHandler.class);

    @BeforeEach
    void setUp() {
        when(instanceHandler.handle(any(), any(), any()))
                .thenReturn(MessageStream.just(
                        new GenericCommandResultMessage(new MessageType(String.class), "handled")
                ));
        when(creationalHandler.handle(any(), any()))
                .thenReturn(MessageStream.just(
                        new GenericCommandResultMessage(new MessageType(String.class), "created")
                ));
    }

    private EntityMetamodelBuilder<TestEntity> testSubjectBuilder() {
        return ConcreteEntityMetamodel.forEntityClass(TestEntity.class)
                                      .instanceCommandHandler(INSTANCE_COMMAND, instanceHandler)
                                      .creationalCommandHandler(CREATIONAL_COMMAND, creationalHandler);
    }

    @Test
    void interceptorProceedingInvokesHandler() {
        // given...
        CommandMessage testCommand = new GenericCommandMessage(new MessageType(INSTANCE_COMMAND), "payload");
        EntityMetamodel<TestEntity> testSubject = testSubjectBuilder()
                .commandHandlerInterceptor((command, entity, context, chain) -> chain.proceed(command, entity, context))
                .build();
        // when...
        MessageStream.Single<CommandResultMessage> result = testSubject.handleInstance(testCommand, entity, context);
        // then...
        Object resultPayload = result.asCompletableFuture().orTimeout(50, TimeUnit.MILLISECONDS).join()
                                     .message().payload();
        assertThat(resultPayload).isEqualTo("handled");
        verify(instanceHandler).handle(testCommand, entity, context);
    }

    @Test
    void interceptorShortCircuitingPreventsHandlerInvocation() {
        // given...
        CommandMessage testCommand = new GenericCommandMessage(new MessageType(INSTANCE_COMMAND), "payload");
        EntityMetamodel<TestEntity> testSubject = testSubjectBuilder()
                .commandHandlerInterceptor((command, entity, context, chain) -> MessageStream.just(
                        new GenericCommandResultMessage(new MessageType(String.class), "intercepted")
                ))
                .build();
        // when...
        MessageStream.Single<CommandResultMessage> result = testSubject.handleInstance(testCommand, entity, context);
        // then...
        Object resultPayload = result.asCompletableFuture().orTimeout(50, TimeUnit.MILLISECONDS).join()
                                     .message().payload();
        assertThat(resultPayload).isEqualTo("intercepted");
        verify(instanceHandler, never()).handle(any(), any(), any());
    }

    @Test
    void multipleInterceptorsAreInvokedInRegistrationOrder() {
        // given...
        CommandMessage testCommand = new GenericCommandMessage(new MessageType(INSTANCE_COMMAND), "payload");
        List<String> invocations = new ArrayList<>();
        EntityCommandHandlerInterceptor<TestEntity> first = (command, ent, ctx, chain) -> {
            invocations.add("first");
            return chain.proceed(command, ent, ctx);
        };
        EntityCommandHandlerInterceptor<TestEntity> second = (command, ent, ctx, chain) -> {
            invocations.add("second");
            return chain.proceed(command, ent, ctx);
        };
        EntityMetamodel<TestEntity> testSubject = testSubjectBuilder()
                .commandHandlerInterceptor(first)
                .commandHandlerInterceptor(second)
                .build();
        // when...
        testSubject.handleInstance(testCommand, entity, context).asCompletableFuture().join();
        // then...
        assertThat(invocations).containsExactly("first", "second");
    }

    @Test
    void interceptorAlsoWrapsCreationalDispatchWithNullEntity() {
        // given...
        CommandMessage testCommand = new GenericCommandMessage(new MessageType(CREATIONAL_COMMAND), "payload");
        EntityCommandHandlerInterceptor<TestEntity> interceptor = (command, entity, context, chain) -> {
            assertThat(entity).isNull();
            return chain.proceed(command, entity, context);
        };
        EntityMetamodel<TestEntity> testSubject = testSubjectBuilder().commandHandlerInterceptor(interceptor).build();
        // when...
        MessageStream.Single<CommandResultMessage> result = testSubject.handleCreate(testCommand, context);
        // then...
        Object resultPayload = result.asCompletableFuture().orTimeout(50, TimeUnit.MILLISECONDS).join()
                                     .message().payload();
        assertThat(resultPayload).isEqualTo("created");
        verify(creationalHandler).handle(testCommand, context);
    }

    @Test
    void interceptorThrowingSynchronouslyResultsInFailedMessageStreamInsteadOfPropagating() {
        // given...
        CommandMessage testCommand = new GenericCommandMessage(new MessageType(INSTANCE_COMMAND), "payload");
        EntityCommandHandlerInterceptor<TestEntity> interceptor = (command, ent, ctx, chain) -> {
            throw new IllegalStateException("Interceptor exploded");
        };
        EntityMetamodel<TestEntity> testSubject = testSubjectBuilder().commandHandlerInterceptor(interceptor).build();
        // when/then...
        MessageStreamTestUtils.assertCompletedExceptionally(
                testSubject.handleInstance(testCommand, entity, context),
                IllegalStateException.class,
                "Interceptor exploded"
        );
    }

    @Test
    void parentInterceptorIsInvokedBeforeChildInterceptorForCommandRoutedToChild() {
        // given...
        CommandMessage testCommand = new GenericCommandMessage(new MessageType(CHILD_COMMAND), "payload");
        List<String> invocations = new ArrayList<>();
        TestChildEntity childEntity = new TestChildEntity();
        @SuppressWarnings("unchecked")
        EntityCommandHandler<TestChildEntity> childHandler = mock(EntityCommandHandler.class);
        when(childHandler.handle(any(), any(), any())).thenReturn(
                MessageStream.just(new GenericCommandResultMessage(new MessageType(String.class), "child-handled"))
        );
        EntityMetamodel<TestChildEntity> childMetamodel = ConcreteEntityMetamodel
                .forEntityClass(TestChildEntity.class)
                .instanceCommandHandler(CHILD_COMMAND, childHandler)
                .commandHandlerInterceptor((command, ent, ctx, chain) -> {
                    invocations.add("child");
                    return chain.proceed(command, ent, ctx);
                })
                .build();
        @SuppressWarnings("unchecked")
        ChildEntityFieldDefinition<TestEntity, TestChildEntity> fieldDefinition =
                mock(ChildEntityFieldDefinition.class);
        when(fieldDefinition.getChildValue(any())).thenReturn(childEntity);
        EntityMetamodel<TestEntity> testSubject = ConcreteEntityMetamodel
                .forEntityClass(TestEntity.class)
                .commandHandlerInterceptor((command, entity, context, chain) -> {
                    invocations.add("parent");
                    return chain.proceed(command, entity, context);
                })
                .addChild(SingleEntityChildMetamodel.forEntityModel(TestEntity.class, childMetamodel)
                                                    .childEntityFieldDefinition(fieldDefinition)
                                                    .build())
                .build();
        // when...
        MessageStream.Single<CommandResultMessage> result =
                testSubject.handleInstance(testCommand, entity, context);
        // then...
        Object resultPayload = result.asCompletableFuture().orTimeout(50, TimeUnit.MILLISECONDS).join()
                                     .message().payload();
        assertThat(resultPayload).isEqualTo("child-handled");
        assertThat(invocations).containsExactly("parent", "child");
        verify(childHandler).handle(testCommand, childEntity, context);
    }

    private static class TestEntity {

    }

    private static class TestChildEntity {

    }
}

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
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.modelling.EntityEvolver;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link EntityCommandHandlerInterceptor} support as provided in the
 * {@link PolymorphicEntityMetamodel}.
 *
 * @author Steven van Beelen
 */
class PolymorphicEntityMetamodelTest {

    public static final QualifiedName SUPER_TYPE_INSTANCE_COMMAND = new QualifiedName("SuperTypeInstanceCommand");
    public static final QualifiedName CONCRETE_ONE_INSTANCE_COMMAND = new QualifiedName("ConcreteOneInstanceCommand");
    public static final QualifiedName CONCRETE_TWO_INSTANCE_COMMAND = new QualifiedName("ConcreteTwoInstanceCommand");

    public static final QualifiedName SUPER_TYPE_CREATIONAL_COMMAND = new QualifiedName("SuperTypeCreationalCommand");
    public static final QualifiedName CONCRETE_ONE_CREATIONAL_COMMAND = new QualifiedName("ConcreteOneCreationalCommand");
    public static final QualifiedName CONCRETE_TWO_CREATIONAL_COMMAND = new QualifiedName("ConcreteTwoCreationalCommand");

    public static final QualifiedName SUPER_TYPE_EVENT = new QualifiedName("SuperTypeEvent");
    public static final QualifiedName CONCRETE_ONE_EVENT = new QualifiedName("ConcreteOneEvent");
    public static final QualifiedName CONCRETE_TWO_EVENT = new QualifiedName("ConcreteTwoEvent");

    private final EntityMetamodel<ConcreteTestEntityOne> concreteTestEntityOneEntityMetamodel = mock();
    private final EntityMetamodel<ConcreteTestEntityTwo> concreteTestEntityTwoEntityMetamodel = mock();
    private final EntityCommandHandler<AbstractTestEntity> entityInstanceCommandHandler = mock();
    private final CommandHandler entityCreationalCommandHandler = mock(CommandHandler.class);
    private final EntityEvolver<AbstractTestEntity> entityEvolver = mock();

    private EntityMetamodel<AbstractTestEntity> polymorphicMetamodel;

    @BeforeEach
    public void setup() {
        when(concreteTestEntityOneEntityMetamodel.entityType()).thenReturn(ConcreteTestEntityOne.class);
        when(concreteTestEntityTwoEntityMetamodel.entityType()).thenReturn(ConcreteTestEntityTwo.class);

        // Set entity model responses
        when(concreteTestEntityOneEntityMetamodel.handleInstance(any(), any(), any())).thenReturn(
                MessageStream.just(new GenericCommandResultMessage(new MessageType("ConcreteOneResult"),
                                                                   "concrete-one")));
        when(concreteTestEntityOneEntityMetamodel.handleCreate(any(), any())).thenReturn(
                MessageStream.just(new GenericCommandResultMessage(new MessageType("ConcreteOneResult"),
                                                                   "concrete-one-create")));
        when(concreteTestEntityTwoEntityMetamodel.handleInstance(any(), any(), any())).thenReturn(
                MessageStream.just(new GenericCommandResultMessage(new MessageType("ConcreteTwoResult"),
                                                                   "concrete-two")));
        when(concreteTestEntityTwoEntityMetamodel.handleCreate(any(), any())).thenReturn(
                MessageStream.just(new GenericCommandResultMessage(new MessageType("ConcreteTwoResult"),
                                                                   "concrete-two-create")));

        // Set supported commands properly
        when(concreteTestEntityOneEntityMetamodel.supportedCreationalCommands()).thenReturn(Set.of(
                CONCRETE_ONE_CREATIONAL_COMMAND));
        when(concreteTestEntityOneEntityMetamodel.supportedInstanceCommands()).thenReturn(Set.of(
                CONCRETE_ONE_INSTANCE_COMMAND));
        when(concreteTestEntityOneEntityMetamodel.supportedCommands()).thenReturn(Set.of(
                CONCRETE_ONE_CREATIONAL_COMMAND,
                CONCRETE_ONE_INSTANCE_COMMAND
        ));
        when(concreteTestEntityTwoEntityMetamodel.supportedCreationalCommands()).thenReturn(Set.of(
                CONCRETE_TWO_CREATIONAL_COMMAND));
        when(concreteTestEntityTwoEntityMetamodel.supportedInstanceCommands()).thenReturn(Set.of(
                CONCRETE_TWO_INSTANCE_COMMAND));
        when(concreteTestEntityTwoEntityMetamodel.supportedCommands()).thenReturn(Set.of(
                CONCRETE_TWO_CREATIONAL_COMMAND,
                CONCRETE_TWO_INSTANCE_COMMAND
        ));

        when(entityInstanceCommandHandler.handle(any(), any(), any())).thenReturn(
                MessageStream.just(new GenericCommandResultMessage(new MessageType("SuperTypeResult"),
                                                                   "super-type")));
        when(entityCreationalCommandHandler.handle(any(), any())).thenReturn(
                MessageStream.just(new GenericCommandResultMessage(new MessageType("SuperTypeResult"),
                                                                   "super-type-create")));

        when(entityEvolver.evolve(any(), any(), any())).thenAnswer(answ -> answ.getArgument(0));


        this.polymorphicMetamodel = PolymorphicEntityMetamodel
                .forSuperType(AbstractTestEntity.class)
                .addConcreteType(concreteTestEntityOneEntityMetamodel)
                .addConcreteType(concreteTestEntityTwoEntityMetamodel)
                .entityEvolver(entityEvolver)
                .instanceCommandHandler(SUPER_TYPE_INSTANCE_COMMAND, entityInstanceCommandHandler)
                .creationalCommandHandler(SUPER_TYPE_CREATIONAL_COMMAND, entityCreationalCommandHandler)
                .build();
    }

    @Test
    void canHandleInstanceCommandForConcreteTypeOne() {
        CommandMessage commandMessage = new GenericCommandMessage(new MessageType(CONCRETE_ONE_INSTANCE_COMMAND),
                                                                  "concrete-one-instance");

        ProcessingContext context = StubProcessingContext.forMessage(commandMessage);
        ConcreteTestEntityOne entity = new ConcreteTestEntityOne();
        MessageStream<CommandResultMessage> result = polymorphicMetamodel.handleInstance(commandMessage,
                                                                                         entity,
                                                                                         context);

        Object resultPayload = result.first()
                                     .asCompletableFuture().orTimeout(50, TimeUnit.MILLISECONDS).join()
                                     .message().payload();
        assertThat(resultPayload).isEqualTo("concrete-one");
        verify(concreteTestEntityOneEntityMetamodel, times(1)).handleInstance(eq(commandMessage), any(), eq(context));
        verify(concreteTestEntityOneEntityMetamodel, times(0)).handleCreate(eq(commandMessage), eq(context));
        verify(concreteTestEntityTwoEntityMetamodel, times(0)).handleInstance(eq(commandMessage), any(), eq(context));
        verify(concreteTestEntityTwoEntityMetamodel, times(0)).handleCreate(eq(commandMessage), eq(context));
        verify(entityInstanceCommandHandler, times(0)).handle(eq(commandMessage), any(), eq(context));
        verify(entityCreationalCommandHandler, times(0)).handle(eq(commandMessage), eq(context));
    }

    @Test
    void canHandleCreationalCommandForConcreteTypeOne() {
        CommandMessage commandMessage = new GenericCommandMessage(new MessageType(CONCRETE_ONE_CREATIONAL_COMMAND),
                                                                  "concrete-one-creational");

        ProcessingContext context = new StubProcessingContext();
        MessageStream<CommandResultMessage> result = polymorphicMetamodel.handleCreate(commandMessage, context);

        Object resultPayload = result.first()
                                     .asCompletableFuture().orTimeout(50, TimeUnit.MILLISECONDS).join()
                                     .message().payload();
        assertThat(resultPayload).isEqualTo("concrete-one-create");
        verify(concreteTestEntityOneEntityMetamodel, times(0)).handleInstance(eq(commandMessage), any(), eq(context));
        verify(concreteTestEntityOneEntityMetamodel, times(1)).handleCreate(eq(commandMessage), eq(context));
        verify(concreteTestEntityTwoEntityMetamodel, times(0)).handleInstance(eq(commandMessage), any(), eq(context));
        verify(concreteTestEntityTwoEntityMetamodel, times(0)).handleCreate(eq(commandMessage), eq(context));
        verify(entityInstanceCommandHandler, times(0)).handle(eq(commandMessage), any(), eq(context));
        verify(entityCreationalCommandHandler, times(0)).handle(eq(commandMessage), eq(context));
    }


    @Test
    void canHandleInstanceCommandForConcreteTypeTwo() {
        CommandMessage commandMessage = new GenericCommandMessage(new MessageType(CONCRETE_TWO_INSTANCE_COMMAND),
                                                                  "concrete-two-instance");

        ProcessingContext context = StubProcessingContext.forMessage(commandMessage);
        ConcreteTestEntityTwo entity = new ConcreteTestEntityTwo();
        MessageStream<CommandResultMessage> result = polymorphicMetamodel.handleInstance(commandMessage,
                                                                                         entity,
                                                                                         context);

        Object resultPayload = result.first()
                                     .asCompletableFuture().orTimeout(50, TimeUnit.MILLISECONDS).join()
                                     .message().payload();
        assertThat(resultPayload).isEqualTo("concrete-two");
        verify(concreteTestEntityOneEntityMetamodel, times(0)).handleInstance(eq(commandMessage), any(), eq(context));
        verify(concreteTestEntityOneEntityMetamodel, times(0)).handleCreate(eq(commandMessage), eq(context));
        verify(concreteTestEntityTwoEntityMetamodel, times(1)).handleInstance(eq(commandMessage), any(), eq(context));
        verify(concreteTestEntityTwoEntityMetamodel, times(0)).handleCreate(eq(commandMessage), eq(context));
        verify(entityInstanceCommandHandler, times(0)).handle(eq(commandMessage), any(), eq(context));
        verify(entityCreationalCommandHandler, times(0)).handle(eq(commandMessage), eq(context));
    }

    @Test
    void canHandleCreationalCommandForConcreteTypeTwo() {
        CommandMessage commandMessage = new GenericCommandMessage(new MessageType(CONCRETE_TWO_CREATIONAL_COMMAND),
                                                                  "concrete-two-creational");

        ProcessingContext context = new StubProcessingContext();
        MessageStream<CommandResultMessage> result = polymorphicMetamodel.handleCreate(commandMessage, context);

        Object resultPayload = result.first()
                                     .asCompletableFuture().orTimeout(50, TimeUnit.MILLISECONDS).join()
                                     .message().payload();
        assertThat(resultPayload).isEqualTo("concrete-two-create");
        verify(concreteTestEntityOneEntityMetamodel, times(0)).handleInstance(eq(commandMessage), any(), eq(context));
        verify(concreteTestEntityOneEntityMetamodel, times(0)).handleCreate(eq(commandMessage), eq(context));
        verify(concreteTestEntityTwoEntityMetamodel, times(0)).handleInstance(eq(commandMessage), any(), eq(context));
        verify(concreteTestEntityTwoEntityMetamodel, times(1)).handleCreate(eq(commandMessage), eq(context));
        verify(entityInstanceCommandHandler, times(0)).handle(eq(commandMessage), any(), eq(context));
        verify(entityCreationalCommandHandler, times(0)).handle(eq(commandMessage), eq(context));
    }

    @Test
    void canHandleInstanceSuperCommandForConcreteTypeOne() {
        CommandMessage commandMessage = new GenericCommandMessage(new MessageType(SUPER_TYPE_INSTANCE_COMMAND),
                                                                  "concrete-one");

        ProcessingContext context = StubProcessingContext.forMessage(commandMessage);
        ConcreteTestEntityOne entity = new ConcreteTestEntityOne();
        MessageStream<CommandResultMessage> result = polymorphicMetamodel.handleInstance(commandMessage,
                                                                                         entity,
                                                                                         context);

        Object resultPayload = result.first()
                                     .asCompletableFuture().orTimeout(50, TimeUnit.MILLISECONDS).join()
                                     .message().payload();
        assertThat(resultPayload).isEqualTo("super-type");
        verify(entityInstanceCommandHandler).handle(eq(commandMessage), same(entity), eq(context));
        verify(concreteTestEntityOneEntityMetamodel, times(0)).handleInstance(eq(commandMessage), any(), eq(context));
        verify(concreteTestEntityOneEntityMetamodel, times(0)).handleCreate(eq(commandMessage), eq(context));
        verify(concreteTestEntityTwoEntityMetamodel, times(0)).handleInstance(eq(commandMessage), any(), eq(context));
        verify(concreteTestEntityTwoEntityMetamodel, times(0)).handleCreate(eq(commandMessage), eq(context));
        verify(entityInstanceCommandHandler, times(1)).handle(eq(commandMessage), any(), eq(context));
        verify(entityCreationalCommandHandler, times(0)).handle(eq(commandMessage), eq(context));
    }

    @Test
    void canHandleCreationalSuperCommand() {
        CommandMessage commandMessage = new GenericCommandMessage(new MessageType(SUPER_TYPE_CREATIONAL_COMMAND),
                                                                  "super-type");

        ProcessingContext context = new StubProcessingContext();
        MessageStream<CommandResultMessage> result = polymorphicMetamodel.handleCreate(commandMessage, context);

        Object resultPayload = result.first()
                                     .asCompletableFuture().orTimeout(50, TimeUnit.MILLISECONDS).join()
                                     .message().payload();
        assertThat(resultPayload).isEqualTo("super-type-create");
        verify(entityCreationalCommandHandler).handle(eq(commandMessage), eq(context));
        verify(concreteTestEntityOneEntityMetamodel, times(0)).handleInstance(eq(commandMessage), any(), eq(context));
        verify(concreteTestEntityOneEntityMetamodel, times(0)).handleCreate(eq(commandMessage), eq(context));
        verify(concreteTestEntityTwoEntityMetamodel, times(0)).handleInstance(eq(commandMessage), any(), eq(context));
        verify(concreteTestEntityTwoEntityMetamodel, times(0)).handleCreate(eq(commandMessage), eq(context));
        verify(entityInstanceCommandHandler, times(0)).handle(eq(commandMessage), any(), eq(context));
        verify(entityCreationalCommandHandler, times(1)).handle(eq(commandMessage), eq(context));
    }

    @Test
    void canHandleInstanceSuperCommandForConcreteTypeTwo() {
        CommandMessage commandMessage = new GenericCommandMessage(new MessageType(SUPER_TYPE_INSTANCE_COMMAND),
                                                                  "concrete-two");

        ProcessingContext context = StubProcessingContext.forMessage(commandMessage);
        ConcreteTestEntityTwo entity = new ConcreteTestEntityTwo();
        MessageStream<CommandResultMessage> result = polymorphicMetamodel.handleInstance(commandMessage,
                                                                                         entity,
                                                                                         context);

        Object resultPayload = result.first()
                                     .asCompletableFuture().orTimeout(50, TimeUnit.MILLISECONDS).join()
                                     .message().payload();
        assertThat(resultPayload).isEqualTo("super-type");
        verify(entityInstanceCommandHandler).handle(eq(commandMessage), same(entity), eq(context));
        verify(concreteTestEntityOneEntityMetamodel, times(0)).handleInstance(eq(commandMessage), any(), eq(context));
        verify(concreteTestEntityTwoEntityMetamodel, times(0)).handleInstance(eq(commandMessage), any(), eq(context));
    }

    @Test
    void commandHandlerInterceptorRegisteredOnPolymorphicBuilderInterceptsSuperTypeCommand() {
        List<String> invocations = new ArrayList<>();
        polymorphicMetamodel = PolymorphicEntityMetamodel
                .forSuperType(AbstractTestEntity.class)
                .addConcreteType(concreteTestEntityOneEntityMetamodel)
                .addConcreteType(concreteTestEntityTwoEntityMetamodel)
                .instanceCommandHandler(SUPER_TYPE_INSTANCE_COMMAND, entityInstanceCommandHandler)
                .commandHandlerInterceptor((command, entity, ctx, chain) -> {
                    invocations.add("intercepted");
                    return chain.proceed(command, entity, ctx);
                })
                .build();

        CommandMessage commandMessage = new GenericCommandMessage(new MessageType(SUPER_TYPE_INSTANCE_COMMAND),
                                                                  "concrete-one");
        ProcessingContext context = StubProcessingContext.forMessage(commandMessage);
        ConcreteTestEntityOne entity = new ConcreteTestEntityOne();

        MessageStream<CommandResultMessage> result = polymorphicMetamodel.handleInstance(commandMessage,
                                                                                         entity,
                                                                                         context);

        Object resultPayload = result.first()
                                     .asCompletableFuture().orTimeout(50, TimeUnit.MILLISECONDS).join()
                                     .message().payload();
        assertThat(resultPayload).isEqualTo("super-type");
        assertThat(invocations).containsExactly("intercepted");
    }

    @Test
    void commandHandlerInterceptorRegisteredOnPolymorphicBuilderDoesNotInterceptConcreteTypeOnlyCommand() {
        List<String> invocations = new ArrayList<>();
        polymorphicMetamodel = PolymorphicEntityMetamodel
                .forSuperType(AbstractTestEntity.class)
                .addConcreteType(concreteTestEntityOneEntityMetamodel)
                .addConcreteType(concreteTestEntityTwoEntityMetamodel)
                .instanceCommandHandler(SUPER_TYPE_INSTANCE_COMMAND, entityInstanceCommandHandler)
                .commandHandlerInterceptor((command, entity, ctx, chain) -> {
                    invocations.add("intercepted");
                    return chain.proceed(command, entity, ctx);
                })
                .build();

        CommandMessage commandMessage = new GenericCommandMessage(new MessageType(CONCRETE_ONE_INSTANCE_COMMAND),
                                                                  "concrete-one-instance");
        ProcessingContext context = StubProcessingContext.forMessage(commandMessage);
        ConcreteTestEntityOne entity = new ConcreteTestEntityOne();

        polymorphicMetamodel.handleInstance(commandMessage, entity, context);

        // Dispatched straight to the (mocked) concrete-type metamodel; the super type's own interceptor never runs.
        assertThat(invocations).isEmpty();
    }

    @Test
    void throwsWrongPolymorphicTypeExceptionForWrongTypeDuringCommand() {
        CommandMessage commandMessage = new GenericCommandMessage(
                new MessageType(CONCRETE_TWO_INSTANCE_COMMAND), "concrete-two");
        ProcessingContext context = StubProcessingContext.forMessage(commandMessage);
        ConcreteTestEntityOne entity = new ConcreteTestEntityOne();

        MessageStream.Single<CommandResultMessage> commandResultMessageSingle = polymorphicMetamodel.handleInstance(
                commandMessage, entity, context
        );
        MessageStreamTestUtils.assertCompletedExceptionally(commandResultMessageSingle,
                                                            WrongPolymorphicEntityTypeException.class);
    }

    @Test
    void callsSuperTypeAndConcreteOneEntityEvolverForConcreteTypeOne() {
        EventMessage eventMessage = new GenericEventMessage(new MessageType(CONCRETE_ONE_EVENT), "event");
        ConcreteTestEntityOne entity = new ConcreteTestEntityOne();
        ProcessingContext context = StubProcessingContext.forMessage(eventMessage);

        polymorphicMetamodel.evolve(entity, eventMessage, context);

        InOrder inOrder = inOrder(entityEvolver,
                                  concreteTestEntityTwoEntityMetamodel,
                                  concreteTestEntityOneEntityMetamodel);
        inOrder.verify(entityEvolver).evolve(eq(entity), eq(eventMessage), eq(context));
        inOrder.verify(concreteTestEntityOneEntityMetamodel).evolve(eq(entity), eq(eventMessage), eq(context));
        inOrder.verify(concreteTestEntityTwoEntityMetamodel, times(0)).evolve(any(), any(), any());
    }

    @Test
    void callsSuperTypeAndConcreteOneEntityEvolverForConcreteTypeTwo() {
        EventMessage eventMessage = new GenericEventMessage(new MessageType(CONCRETE_TWO_EVENT), "event");
        ConcreteTestEntityTwo entity = new ConcreteTestEntityTwo();
        ProcessingContext context = StubProcessingContext.forMessage(eventMessage);

        polymorphicMetamodel.evolve(entity, eventMessage, context);

        InOrder inOrder = inOrder(entityEvolver,
                                  concreteTestEntityOneEntityMetamodel,
                                  concreteTestEntityTwoEntityMetamodel);
        inOrder.verify(entityEvolver).evolve(eq(entity), eq(eventMessage), eq(context));
        inOrder.verify(concreteTestEntityTwoEntityMetamodel).evolve(eq(entity), eq(eventMessage), eq(context));
        inOrder.verify(concreteTestEntityOneEntityMetamodel, times(0)).evolve(any(), any(), any());
    }

    @Test
    void superTypeEvolverCanBeUsedToMorphTheConcreteType() {
        reset(entityEvolver,
              concreteTestEntityOneEntityMetamodel,
              concreteTestEntityTwoEntityMetamodel);
        when(entityEvolver.evolve(argThat(c -> c instanceof ConcreteTestEntityOne),
                                  any(),
                                  any())).thenReturn(new ConcreteTestEntityTwo());
        when(concreteTestEntityOneEntityMetamodel.evolve(any(),
                                                         any(),
                                                         any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(concreteTestEntityTwoEntityMetamodel.evolve(any(),
                                                         any(),
                                                         any())).thenAnswer(invocation -> invocation.getArgument(0));
        EventMessage eventMessage = new GenericEventMessage(new MessageType(SUPER_TYPE_EVENT), "event");
        ConcreteTestEntityOne entity = new ConcreteTestEntityOne();
        ProcessingContext context = StubProcessingContext.forMessage(eventMessage);
        AbstractTestEntity result = polymorphicMetamodel.evolve(entity, eventMessage, context);
        assertThat(result).isInstanceOf(ConcreteTestEntityTwo.class);
    }

    @Test
    void concreteTypeEvolverCanBeUsedToMorphTheConcreteType() {
        reset(entityEvolver,
              concreteTestEntityOneEntityMetamodel,
              concreteTestEntityTwoEntityMetamodel);
        when(entityEvolver.evolve(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(concreteTestEntityOneEntityMetamodel.evolve(any(),
                                                         any(),
                                                         any())).thenAnswer(invocation -> new ConcreteTestEntityTwo());
        when(concreteTestEntityTwoEntityMetamodel.evolve(any(),
                                                         any(),
                                                         any())).thenAnswer(invocation -> invocation.getArgument(0));
        EventMessage eventMessage = new GenericEventMessage(new MessageType(SUPER_TYPE_EVENT), "event");
        ConcreteTestEntityOne entity = new ConcreteTestEntityOne();
        ProcessingContext context = StubProcessingContext.forMessage(eventMessage);
        AbstractTestEntity result = polymorphicMetamodel.evolve(entity, eventMessage, context);
        assertThat(result).isInstanceOf(ConcreteTestEntityTwo.class);
    }

    @Test
    void returnsSuperTypeAsEntityType() {
        assertThat(polymorphicMetamodel.entityType()).isEqualTo(AbstractTestEntity.class);
    }

    @Test
    void returnsAllSupportedCommands() {
        Set<QualifiedName> expectedCommands = Set.of(
                SUPER_TYPE_CREATIONAL_COMMAND,
                SUPER_TYPE_INSTANCE_COMMAND,
                CONCRETE_TWO_CREATIONAL_COMMAND,
                CONCRETE_ONE_CREATIONAL_COMMAND,
                CONCRETE_TWO_INSTANCE_COMMAND,
                CONCRETE_ONE_INSTANCE_COMMAND
        );
        assertThat(polymorphicMetamodel.supportedCommands()).isEqualTo(expectedCommands);
    }

    @Test
    void correctlyDescribesComponent() {
        MockComponentDescriptor descriptor = new MockComponentDescriptor();
        polymorphicMetamodel.describeTo(descriptor);

        assertThat((Object) descriptor.getProperty("entityType")).isEqualTo(AbstractTestEntity.class);
        EntityMetamodel<AbstractTestEntity> superTypeMetamodel = descriptor.getProperty("superTypeMetamodel");
        superTypeMetamodel.describeTo(descriptor);

        assertThat((Object) descriptor.getProperty("entityEvolver")).isEqualTo(entityEvolver);
        assertThat((Object) descriptor.getProperty("commandHandlers")).isEqualTo(Map.of(
                SUPER_TYPE_INSTANCE_COMMAND, entityInstanceCommandHandler
        ));

        assertThat((Object) descriptor.getProperty("polymorphicMetamodels")).isEqualTo(Map.of(
                ConcreteTestEntityOne.class, concreteTestEntityOneEntityMetamodel,
                ConcreteTestEntityTwo.class, concreteTestEntityTwoEntityMetamodel
        ));
    }

    @Nested
    @DisplayName("Builder verifications")
    public class BuilderVerifications {

        @Test
        void cannotAddConcreteTypeWithSameEntityType() {
            PolymorphicEntityMetamodelBuilder<AbstractTestEntity> builder = PolymorphicEntityMetamodel
                    .forSuperType(AbstractTestEntity.class)
                    .addConcreteType(concreteTestEntityOneEntityMetamodel)
                    .addConcreteType(concreteTestEntityTwoEntityMetamodel);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> builder.addConcreteType(concreteTestEntityOneEntityMetamodel));
        }

        @Test
        void cannotAddConcreteTypeWithConflictingCreationalCommandHandlerOfOther() {
            when(concreteTestEntityOneEntityMetamodel.supportedCreationalCommands()).thenReturn(Set.of(
                    CONCRETE_ONE_CREATIONAL_COMMAND, CONCRETE_TWO_CREATIONAL_COMMAND));
            when(concreteTestEntityTwoEntityMetamodel.supportedCreationalCommands()).thenReturn(Set.of(
                    CONCRETE_TWO_CREATIONAL_COMMAND));
            PolymorphicEntityMetamodelBuilder<AbstractTestEntity> builder = PolymorphicEntityMetamodel
                    .forSuperType(AbstractTestEntity.class)
                    .addConcreteType(concreteTestEntityOneEntityMetamodel);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> builder.addConcreteType(concreteTestEntityTwoEntityMetamodel));
        }

        @Test
        void canNotAddNullChildEntityModel() {
            PolymorphicEntityMetamodelBuilder<AbstractTestEntity> builder = PolymorphicEntityMetamodel.forSuperType(
                    AbstractTestEntity.class);

            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> {
                //noinspection DataFlowIssue
                builder.addConcreteType(null);
            });
        }
    }

    public static class AbstractTestEntity {

    }

    public static class ConcreteTestEntityOne extends AbstractTestEntity {

    }

    public static class ConcreteTestEntityTwo extends AbstractTestEntity {

    }
}
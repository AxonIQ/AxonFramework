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

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating {@link EntityCommandHandlerInterceptor#of(MessageHandlerInterceptor)}.
 *
 * @author Mateusz Nowak
 */
class EntityCommandHandlerInterceptorTest {

    private static final QualifiedName INSTANCE_COMMAND = new QualifiedName("InstanceCommand");

    private final TestEntity entity = new TestEntity();
    private final ProcessingContext context = new StubProcessingContext();
    private final CommandMessage command = new GenericCommandMessage(new MessageType(INSTANCE_COMMAND), "payload");

    @Nested
    class Of {

        @Test
        void proceedingContinuesTheEntityChainWithTheTargetedEntity() {
            // given
            RecordingEntityChain chain = new RecordingEntityChain();
            EntityCommandHandlerInterceptor<TestEntity> testSubject =
                    EntityCommandHandlerInterceptor.of(proceedingInterceptor());

            // when
            Object result = payloadOf(testSubject.interceptOnHandle(command, entity, context, chain));

            // then
            assertThat(result).isEqualTo("handled");
            assertThat(chain.commands).containsExactly(command);
            assertThat(chain.entities).containsExactly(entity);
            assertThat(chain.contexts).containsExactly(context);
        }

        @Test
        void proceedingWithAReplacedCommandForwardsItTogetherWithTheTargetedEntity() {
            // given
            CommandMessage replacement = new GenericCommandMessage(new MessageType(INSTANCE_COMMAND), "replaced");
            MessageHandlerInterceptor<CommandMessage> replacing = (message, ctx, interceptorChain) ->
                    interceptorChain.proceed(replacement, ctx);
            RecordingEntityChain chain = new RecordingEntityChain();
            EntityCommandHandlerInterceptor<TestEntity> testSubject = EntityCommandHandlerInterceptor.of(replacing);

            // when
            payloadOf(testSubject.interceptOnHandle(command, entity, context, chain));

            // then
            assertThat(chain.commands).containsExactly(replacement);
            assertThat(chain.entities).containsExactly(entity);
        }

        @Test
        void notProceedingShortCircuitsTheEntityChain() {
            // given
            MessageHandlerInterceptor<CommandMessage> shortCircuiting = (message, ctx, interceptorChain) ->
                    MessageStream.just(new GenericCommandResultMessage(new MessageType(String.class), "intercepted"));
            RecordingEntityChain chain = new RecordingEntityChain();
            EntityCommandHandlerInterceptor<TestEntity> testSubject =
                    EntityCommandHandlerInterceptor.of(shortCircuiting);

            // when
            Object result = payloadOf(testSubject.interceptOnHandle(command, entity, context, chain));

            // then
            assertThat(result).isEqualTo("intercepted");
            assertThat(chain.commands).isEmpty();
        }

        @Test
        void creationalCommandWithoutEntityIsForwardedWithoutEntity() {
            // given
            RecordingEntityChain chain = new RecordingEntityChain();
            EntityCommandHandlerInterceptor<TestEntity> testSubject =
                    EntityCommandHandlerInterceptor.of(proceedingInterceptor());

            // when
            payloadOf(testSubject.interceptOnHandle(command, null, context, chain));

            // then
            assertThat(chain.entities).containsExactly((TestEntity) null);
        }

        @Test
        void interceptorDeclaredForAnyMessageCanBeAdapted() {
            // given
            List<Message> intercepted = new ArrayList<>();
            MessageHandlerInterceptor<Message> anyMessage = (message, ctx, interceptorChain) -> {
                intercepted.add(message);
                return interceptorChain.proceed(message, ctx);
            };
            RecordingEntityChain chain = new RecordingEntityChain();
            EntityCommandHandlerInterceptor<TestEntity> testSubject = EntityCommandHandlerInterceptor.of(anyMessage);

            // when
            payloadOf(testSubject.interceptOnHandle(command, entity, context, chain));

            // then
            assertThat(intercepted).containsExactly(command);
            assertThat(chain.commands).containsExactly(command);
        }

        @Test
        void adaptedInterceptorRegisteredOnEntityMetamodelWrapsTheEntityCommandHandler() {
            // given
            List<String> invocations = new ArrayList<>();
            MessageHandlerInterceptor<CommandMessage> recording = (message, ctx, interceptorChain) -> {
                invocations.add("interceptor");
                return interceptorChain.proceed(message, ctx);
            };
            EntityMetamodel<TestEntity> metamodel =
                    ConcreteEntityMetamodel.forEntityClass(TestEntity.class)
                                           .instanceCommandHandler(INSTANCE_COMMAND, (cmd, ent, ctx) -> {
                                               invocations.add("handler");
                                               return MessageStream.just(new GenericCommandResultMessage(
                                                       new MessageType(String.class), "handled"
                                               ));
                                           })
                                           .commandHandlerInterceptor(EntityCommandHandlerInterceptor.of(recording))
                                           .build();

            // when
            Object result = payloadOf(metamodel.handleInstance(command, entity, context));

            // then
            assertThat(result).isEqualTo("handled");
            assertThat(invocations).containsExactly("interceptor", "handler");
        }

        @Test
        void nullInterceptorIsRejected() {
            // when / then
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> EntityCommandHandlerInterceptor.of(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    private static MessageHandlerInterceptor<CommandMessage> proceedingInterceptor() {
        return (message, ctx, interceptorChain) -> interceptorChain.proceed(message, ctx);
    }

    private static Object payloadOf(MessageStream<?> stream) {
        return stream.first().asCompletableFuture().orTimeout(1, TimeUnit.SECONDS).join().message().payload();
    }

    /**
     * Entity chain recording what it is proceeded with, standing in for the entity's handlers.
     */
    private static class RecordingEntityChain implements EntityCommandHandlerInterceptorChain<TestEntity> {

        private final List<CommandMessage> commands = new ArrayList<>();
        private final List<@Nullable TestEntity> entities = new ArrayList<>();
        private final List<ProcessingContext> contexts = new ArrayList<>();

        @Override
        public MessageStream<?> proceed(CommandMessage command,
                                       @Nullable TestEntity entity,
                                       ProcessingContext context) {
            commands.add(command);
            entities.add(entity);
            contexts.add(context);
            return MessageStream.just(new GenericCommandResultMessage(new MessageType(String.class), "handled"));
        }
    }

    private static class TestEntity {

    }
}

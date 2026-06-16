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

package org.axonframework.messaging.commandhandling.configuration;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.StubLifecycleRegistry;
import org.axonframework.messaging.commandhandling.CommandHandlingComponent;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.commandhandling.interception.InterceptingCommandHandlingComponent;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageHandlingExceptionHandler;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link CommandHandlingModule}.
 */
class SimpleCommandHandlingModuleTest {

    private static final QualifiedName COMMAND_NAME = new QualifiedName(String.class);

    private CommandHandlingModule.SetupPhase setupPhase;
    private CommandHandlingModule.CommandHandlerPhase commandHandlerPhase;

    @BeforeEach
    void setUp() {
        setupPhase = CommandHandlingModule.named("test-subject");
        commandHandlerPhase = setupPhase.commandHandlers();
    }

    @Test
    void nameReturnsModuleName() {
        assertEquals("test-subject", setupPhase.commandHandlers().build().name());
    }

    @Test
    void buildAnnotatedCommandHandlingComponentSucceedsAndRegisters() {
        //noinspection unused
        var myCommandHandlingObject = new Object() {
            @org.axonframework.messaging.commandhandling.annotation.CommandHandler
            public String handle(String command) {
                return command;
            }
        };

        var resultConfig =
                setupPhase.commandHandlers()
                          .autodetectedCommandHandlingComponent(c -> myCommandHandlingObject)
                          .build()
                          .build(MessagingConfigurer.create().build(), new StubLifecycleRegistry());

        Optional<CommandHandlingComponent> optionalHandlingComponent = resultConfig.getOptionalComponent(
                CommandHandlingComponent.class, "CommandHandlingComponent[test-subject]");
        assertTrue(optionalHandlingComponent.isPresent());
        assertTrue(optionalHandlingComponent.get().supportedCommands().contains(COMMAND_NAME));
    }

    @Test
    void namedThrowsNullPointerExceptionForNullModuleName() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> CommandHandlingModule.named(null));
    }

    @Test
    void commandHandlerThrowsNullPointerExceptionForNullCommandName() {
        //noinspection DataFlowIssue
        assertThrows(
                NullPointerException.class,
                () -> commandHandlerPhase.commandHandler(null, (cmd, ctx) -> MessageStream.empty().cast())
        );
    }

    @Test
    void commandHandlerThrowsNullPointerExceptionForNullCommandHandler() {
        //noinspection DataFlowIssue
        assertThrows(
                NullPointerException.class,
                () -> commandHandlerPhase.commandHandler(
                        COMMAND_NAME,
                        (org.axonframework.messaging.commandhandling.CommandHandler) null
                )
        );
    }

    @Test
    void commandHandlingComponentThrowsNullPointerExceptionForNullBuilder() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> commandHandlerPhase.commandHandlingComponent(null));
    }

    @Test
    void autodetectedCommandHandlingComponentThrowsNullPointerExceptionForNullBuilder() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> commandHandlerPhase.autodetectedCommandHandlingComponent(null));
    }

    @Test
    void commandHandlersConsumerThrowsNullPointerExceptionForNullConsumer() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> setupPhase.commandHandlers(null));
    }

    @Nested
    class InterceptedTest {

        private static final GenericCommandMessage SAMPLE_COMMAND =
                new GenericCommandMessage(new MessageType(String.class), "payload");

        @Test
        void singleInterceptorIsInvokedBeforeHandler() {
            // given
            List<String> invocationLog = new ArrayList<>();
            var component = buildModule(commandHandlerPhase
                    .commandHandler(COMMAND_NAME, (cmd, ctx) -> {
                        invocationLog.add("handler");
                        return MessageStream.empty().cast();
                    })
                    .intercepted(cfg -> (msg, ctx, chain) -> {
                        invocationLog.add("interceptor");
                        return chain.proceed(msg, ctx);
                    }));

            // when
            component.handle(SAMPLE_COMMAND, StubProcessingContext.forMessage(SAMPLE_COMMAND)).asCompletableFuture()
                     .orTimeout(5, TimeUnit.SECONDS).join();

            // then
            assertThat(invocationLog).containsExactly("interceptor", "handler");
        }

        @Test
        void multipleInterceptorsAreInvokedInRegistrationOrder() {
            // given
            List<String> invocationLog = new ArrayList<>();
            var component = buildModule(commandHandlerPhase
                    .commandHandler(COMMAND_NAME, (cmd, ctx) -> {
                        invocationLog.add("handler");
                        return MessageStream.empty().cast();
                    })
                    .intercepted(cfg -> (msg, ctx, chain) -> {
                        invocationLog.add("first");
                        return chain.proceed(msg, ctx);
                    })
                    .intercepted(cfg -> (msg, ctx, chain) -> {
                        invocationLog.add("second");
                        return chain.proceed(msg, ctx);
                    }));

            // when
            component.handle(SAMPLE_COMMAND, StubProcessingContext.forMessage(SAMPLE_COMMAND)).asCompletableFuture()
                     .orTimeout(5, TimeUnit.SECONDS).join();

            // then
            assertThat(invocationLog).containsExactly("first", "second", "handler");
        }

        @Test
        void noWrappingWhenNoInterceptorsRegistered() {
            // given / when
            var component = buildModule(commandHandlerPhase
                    .commandHandler(COMMAND_NAME, (cmd, ctx) -> MessageStream.empty()));

            // then
            assertThat(component).isNotInstanceOf(InterceptingCommandHandlingComponent.class);
        }

        @Test
        void wrappingOccursWhenInterceptorIsRegistered() {
            // given / when
            var component = buildModule(commandHandlerPhase
                    .commandHandler(COMMAND_NAME, (cmd, ctx) -> MessageStream.empty())
                    .intercepted(cfg -> (msg, ctx, chain) -> chain.proceed(msg, ctx)));

            // then
            assertThat(component).isInstanceOf(InterceptingCommandHandlingComponent.class);
        }

        @Test
        void interceptorCanShortCircuitHandling() {
            // given
            List<String> invocationLog = new ArrayList<>();
            var component = buildModule(commandHandlerPhase
                    .commandHandler(COMMAND_NAME, (cmd, ctx) -> {
                        invocationLog.add("handler");
                        return MessageStream.empty().cast();
                    })
                    .intercepted(cfg -> (msg, ctx, chain) -> MessageStream.failed(new RuntimeException("access denied"))));

            // when / then
            assertThat(component.handle(SAMPLE_COMMAND, StubProcessingContext.forMessage(SAMPLE_COMMAND))
                                .asCompletableFuture())
                    .failsWithin(5, java.util.concurrent.TimeUnit.SECONDS)
                    .withThrowableOfType(ExecutionException.class)
                    .withCauseInstanceOf(RuntimeException.class)
                    .withMessageContaining("access denied");

            assertThat(invocationLog).isEmpty();
        }

        @SuppressWarnings("DataFlowIssue")
        @Test
        void interceptorNullThrowsNullPointerException() {
            // given / when / then
            assertThrows(NullPointerException.class, () -> commandHandlerPhase.intercepted(null));
        }

        private CommandHandlingComponent buildModule(CommandHandlingModule.CommandHandlerPhase phase) {
            var config = phase.build()
                              .build(MessagingConfigurer.create().build(), new StubLifecycleRegistry());
            return config.getComponent(CommandHandlingComponent.class, "CommandHandlingComponent[test-subject]");
        }
    }

    @Nested
    class WithExceptionHandlerTest {

        private static final QualifiedName COMMAND_NAME_LOCAL = new QualifiedName("test-command");
        private static final GenericCommandMessage SAMPLE_COMMAND =
                new GenericCommandMessage(new MessageType("test-command"), "payload");
        private static final StubProcessingContext STUB_PROCESSING_CONTEXT = new StubProcessingContext();

        private static CommandHandlingComponent buildComponent(CommandHandlingModule.CommandHandlerPhase phase) {
            var moduleName = "test";
            Configuration config = phase.build()
                                        .build(MessagingConfigurer.create().build(), new StubLifecycleRegistry());
            return config.getOptionalComponent(CommandHandlingComponent.class,
                                               "CommandHandlingComponent[" + moduleName + "]")
                         .orElseThrow();
        }

        @Test
        void exceptionHandlerIsInvokedWhenHandlerThrows() {
            // given
            List<String> invocationLog = new ArrayList<>();
            var component = buildComponent(
                    CommandHandlingModule.named("test")
                                        .commandHandlers()
                                        .commandHandler(COMMAND_NAME_LOCAL,
                                                        (cmd, ctx) -> MessageStream.failed(new RuntimeException("handler failed")))
                                        .withExceptionHandler(c -> (cmd, ctx, error) -> {
                                            invocationLog.add("exceptionHandler");
                                            return MessageStream.empty();
                                        })
            );

            // when
            component.handle(SAMPLE_COMMAND, STUB_PROCESSING_CONTEXT);

            // then
            assertThat(invocationLog).containsExactly("exceptionHandler");
        }

        @Test
        void exceptionHandlerCanSuppressException() {
            // given
            var component = buildComponent(
                    CommandHandlingModule.named("test")
                                        .commandHandlers()
                                        .commandHandler(COMMAND_NAME_LOCAL,
                                                        (cmd, ctx) -> MessageStream.failed(new RuntimeException("handler failed")))
                                        .withExceptionHandler(c -> (cmd, ctx, error) -> MessageStream.empty())
            );

            // when - exception handler returns empty, suppressing the error
            var result = component.handle(SAMPLE_COMMAND, STUB_PROCESSING_CONTEXT);
            result.peek(); // force lazy evaluation of the onErrorContinue chain

            // then
            assertThat(result.error()).isEmpty();
        }

        @Test
        void exceptionHandlerCanPropagateError() {
            // given
            var component = buildComponent(
                    CommandHandlingModule.named("test")
                                        .commandHandlers()
                                        .commandHandler(COMMAND_NAME_LOCAL,
                                                        (cmd, ctx) -> MessageStream.failed(new RuntimeException("original")))
                                        .withExceptionHandler(c -> (cmd, ctx, error) -> MessageStream.failed(new IOException("wrapped")))
            );

            // when - exception handler returns a failed stream, the error propagates
            var result = component.handle(SAMPLE_COMMAND, STUB_PROCESSING_CONTEXT);
            result.peek(); // force lazy evaluation of the onErrorContinue chain

            // then
            assertThat(result.error()).isPresent();
            assertThat(result.error().get()).isInstanceOf(IOException.class).hasMessage("wrapped");
        }

        @Test
        void exceptionHandlerCanSubstituteResult() {
            // given
            var substituteResult = new GenericCommandResultMessage(new MessageType("result"), "substitute");
            var component = buildComponent(
                    CommandHandlingModule.named("test")
                                        .commandHandlers()
                                        .commandHandler(COMMAND_NAME_LOCAL,
                                                        (cmd, ctx) -> MessageStream.failed(new RuntimeException("handler failed")))
                                        .withExceptionHandler(c -> (cmd, ctx, error) -> MessageStream.just(substituteResult))
            );

            // when - exception handler returns a result message to substitute
            var result = component.handle(SAMPLE_COMMAND, STUB_PROCESSING_CONTEXT);

            // then - asCompletableFuture() forces evaluation and returns the substituted result
            assertThat(result.asCompletableFuture().orTimeout(1, java.util.concurrent.TimeUnit.SECONDS).join())
                    .isNotNull()
                    .satisfies(entry -> assertThat(entry.message()).isEqualTo(substituteResult));
        }

        @Test
        void exceptionHandlerUnexpectedThrowIsWrappedInFailedStream() {
            // given
            var component = buildComponent(
                    CommandHandlingModule.named("test")
                                        .commandHandlers()
                                        .commandHandler(COMMAND_NAME_LOCAL,
                                                        (cmd, ctx) -> MessageStream.failed(new RuntimeException("original")))
                                        .withExceptionHandler(c -> (cmd, ctx, error) -> {
                                            throw new RuntimeException("unexpected");
                                        })
            );

            // when - exception handler throws unexpectedly, the thrown exception propagates as a failed stream
            var result = component.handle(SAMPLE_COMMAND, STUB_PROCESSING_CONTEXT);
            result.peek(); // force lazy evaluation of the onErrorContinue chain

            // then
            assertThat(result.error()).isPresent();
            assertThat(result.error().get()).isInstanceOf(RuntimeException.class).hasMessage("unexpected");
        }

        @Test
        void genericMessageExceptionHandlerCanBeRegistered() {
            // given - a generic handler typed at Message (not CommandMessage) that could be shared across all message types
            List<String> invocationLog = new ArrayList<>();
            MessageHandlingExceptionHandler<Message> genericHandler = (msg, ctx, error) -> {
                invocationLog.add("generic:" + msg.type().name() + ":" + error.getMessage());
                return MessageStream.empty();
            };
            var component = buildComponent(
                    CommandHandlingModule.named("test")
                                        .commandHandlers()
                                        .commandHandler(COMMAND_NAME_LOCAL,
                                                        (cmd, ctx) -> MessageStream.failed(new RuntimeException("boom")))
                                        .withExceptionHandler(c -> genericHandler)
            );

            // when
            var result = component.handle(SAMPLE_COMMAND, STUB_PROCESSING_CONTEXT);
            result.peek();

            // then
            assertThat(invocationLog).containsExactly("generic:test-command:boom");
            assertThat(result.error()).isEmpty();
        }

        @Test
        void lastRegisteredHandlerSeesExceptionFirst() {
            // given
            List<String> invocationLog = new ArrayList<>();
            var component = buildComponent(
                    CommandHandlingModule.named("test")
                                        .commandHandlers()
                                        .commandHandler(COMMAND_NAME_LOCAL,
                                                        (cmd, ctx) -> MessageStream.failed(new RuntimeException("handler failed")))
                                        .withExceptionHandler(c -> (cmd, ctx, error) -> {
                                            // first registered: outermost interceptor, runs after second propagates
                                            invocationLog.add("first");
                                            return MessageStream.empty();
                                        })
                                        .withExceptionHandler(c -> (cmd, ctx, error) -> {
                                            // second registered: innermost interceptor (closest to handler), sees exception first
                                            invocationLog.add("second");
                                            return MessageStream.failed(error);
                                        })
            );

            // when
            component.handle(SAMPLE_COMMAND, STUB_PROCESSING_CONTEXT);

            // then - last registered handler (innermost) runs first
            assertThat(invocationLog).containsExactly("second", "first");
        }
    }
}

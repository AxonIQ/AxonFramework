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

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.Module;
import org.axonframework.common.configuration.ModuleBuilder;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandHandlingComponent;
import org.axonframework.messaging.commandhandling.CommandHandlingExceptionHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.annotation.AnnotatedCommandHandlingComponent;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlingExceptionHandler;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.conversion.MessageConverter;

import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * A {@link Module} and {@link ModuleBuilder} implementation providing operation to construct a command handling
 * application module.
 * <p>
 * The {@code CommandHandlingModule} follows a builder paradigm, wherein several {@link CommandHandler CommmandHandlers}
 * can be registered in any order.
 * <p>
 * To register command handlers, a similar registration phase switch should be made, by invoking
 * {@link SetupPhase#commandHandlers()}.
 * <p>
 * Here's an example of how to register two command handler lambdas:
 * <pre>
 * CommandHandlingModule.named("my-module")
 *                              .commandHandlers()
 *                              .commandHandler(new QualifiedName(RenameCourseCommand.class),
 *                                              (cmd, context) -> { ...command handling logic... })
 *                              .commandHandler(new QualifiedName(ChangeCourseClassRoomCommand.class),
 *                                              (cmd, context) -> { ...command handling logic... });
 * </pre>
 * <p>
 * Note that users do not have to invoke {@link #build()} themselves when using this interface, as the
 * {@link ApplicationConfigurer} takes care of that.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @since 5.0.0
 */
public interface CommandHandlingModule extends Module, ModuleBuilder<CommandHandlingModule> {

    /**
     * Starts a {@code CommandHandlingModule} module with the given {@code moduleName}.
     *
     * @param moduleName The name of the {@code CommandHandlingModule} under construction.
     * @return The setup phase of this module, for a fluent API.
     */
    static SetupPhase named(String moduleName) {
        return new SimpleCommandHandlingModule(moduleName);
    }

    /**
     * The setup phase of the command handling module.
     * <p>
     * The {@link #commandHandlers()} method allows users to start configuring all the
     * {@link CommandHandler CommandHandlers} for this module.
     */
    interface SetupPhase {

        /**
         * Initiates the command handler configuration phase for this module.
         *
         * @return The command handler phase of this module, for a fluent API.
         */
        CommandHandlerPhase commandHandlers();

        /**
         * Initiates the command handler configuration phase for this module, as well as performing the given
         * {@code configurationLambda} within this phase.
         *
         * @param configurationLambda A consumer of the command handler phase, performing command handler configuration
         *                            right away.
         * @return The command handler phase of this module, for a fluent API.
         */
        default CommandHandlerPhase commandHandlers(Consumer<CommandHandlerPhase> configurationLambda) {
            CommandHandlerPhase commandHandlerPhase = commandHandlers();
            requireNonNull(configurationLambda, "The command handler configuration lambda cannot be null.")
                    .accept(commandHandlerPhase);
            return commandHandlerPhase;
        }
    }

    /**
     * The command handler configuration phase of the command handling module.
     * <p>
     * Every registered {@link CommandHandler} will be subscribed with the {@link CommandBus} of the
     * {@link ApplicationConfigurer} this module is given to.
     * <p>
     * Provides roughly two options for configuring command handlers. Firstly, a command handler can be registered as
     * is, through the {@link #commandHandler(QualifiedName, CommandHandler)} method. Secondly, if the command handler
     * provides components from the {@link Configuration}, a {@link ComponentBuilder builder} of the command handler can
     * be registered through the {@link #commandHandler(QualifiedName, ComponentBuilder)} method.
     */
    interface CommandHandlerPhase extends ModuleBuilder<CommandHandlingModule> {

        /**
         * Registers the given {@code commandHandler} for the given qualified {@code commandName} within this module.
         * <p>
         * Use this command handler registration method when the command handler in question does not require entities
         * or receives entities through another mechanism. Using a {@link MessageTypeResolver} to derive the
         * {@code commandName} is beneficial to ensure consistent naming across handler subscriptions.
         * <p>
         * Once this module is finalized, the command handler will be subscribed with the {@link CommandBus} of the
         * {@link ApplicationConfigurer} the module is registered on.
         *
         * @param commandName    The qualified name of the command the given {@code commandHandler} can handle.
         * @param commandHandler The command handler to register with this module.
         * @return The command handler phase of this builder, for a fluent API.
         */
        default CommandHandlerPhase commandHandler(QualifiedName commandName,
                                                   CommandHandler commandHandler) {
            requireNonNull(commandHandler, "The command handler cannot be null.");
            return commandHandler(commandName, cfg -> commandHandler);
        }

        /**
         * Registers the given {@code commandHandlerBuilder} for the given qualified {@code commandName} within this
         * module.
         * <p>
         * Using a {@link MessageTypeResolver} to derive the {@code commandName} is beneficial to ensure consistent
         * naming across handler subscriptions.
         * <p>
         * Once this module is finalized, the command handler from the {@code commandHandlerBuilder} will be subscribed
         * with the {@link CommandBus} of the {@link ApplicationConfigurer} the module is registered on.
         *
         * @param commandName           The qualified name of the command the {@link CommandHandler} created by the
         *                              given {@code commandHandlerBuilder}.
         * @param commandHandlerBuilder A builder of a {@link CommandHandler}. Provides the {@link Configuration} to
         *                              retrieve components from to use during construction of the command handler.
         * @return The command handler phase of this builder, for a fluent API.
         */
        CommandHandlerPhase commandHandler(
                QualifiedName commandName,
                ComponentBuilder<CommandHandler> commandHandlerBuilder);

        /**
         * Registers the given {@code handlingComponentBuilder} within this module.
         * <p>
         * Use this command handler registration method when the command handling component in question does not require
         * entities or receives entities through another mechanism.
         * <p>
         * Once this module is finalized, the resulting {@link CommandHandlingComponent} from the
         * {@code handlingComponentBuilder} will be subscribed with the {@link CommandBus} of the
         * {@link ApplicationConfigurer} the module is registered on.
         *
         * @param handlingComponentBuilder A builder of a {@link CommandHandlingComponent}. Provides the
         *                                 {@link Configuration} to retrieve components from to use during construction
         *                                 of the command handling component.
         * @return The command handler phase of this builder, for a fluent API.
         */
        CommandHandlerPhase commandHandlingComponent(
                ComponentBuilder<CommandHandlingComponent> handlingComponentBuilder
        );

        /**
         * Registers an interceptor to be applied to the command handling component assembled by this module. Multiple
         * calls accumulate interceptors in registration order.
         *
         * @param interceptorBuilder builder for the interceptor to apply
         * @return the command handler phase of this builder, for a fluent API
         */
        CommandHandlerPhase intercepted(
                ComponentBuilder<MessageHandlerInterceptor<? super CommandMessage>> interceptorBuilder
        );

        /**
         * Registers the given {@code handlingComponentBuilder} as an {@link AnnotatedCommandHandlingComponent} within
         * this module.
         * <p>
         * This will scan the given {@code handlingComponentBuilder} for methods annotated with {@link CommandHandler}
         * and register them as command handlers for the {@link CommandBus} of the {@link ApplicationConfigurer}.
         *
         * @param handlingComponentBuilder A builder of a {@link CommandHandlingComponent}. Provides the
         *                                 {@link Configuration} to retrieve components from to use during construction
         *                                 of the command handling component.
         * @return The command handler phase of this builder, for a fluent API.
         */
        default CommandHandlerPhase autodetectedCommandHandlingComponent(
                ComponentBuilder<Object> handlingComponentBuilder
        ) {
            requireNonNull(handlingComponentBuilder, "The handling component builder cannot be null.");
            return commandHandlingComponent(c -> new AnnotatedCommandHandlingComponent<>(
                    handlingComponentBuilder.build(c),
                    c.getComponent(ParameterResolverFactory.class),
                    c.getOptionalComponent(HandlerDefinition.class)
                     .orElse(ClasspathHandlerDefinition.forClass(c.getClass())),
                    c.getComponent(MessageTypeResolver.class),
                    c.getComponent(MessageConverter.class)
            ));
        }

        /**
         * Wraps the command handling component with the exception handler built by the given {@code handlerBuilder}.
         * When a command handler throws, the exception handler is invoked. Return
         * {@link MessageStream#empty()} to suppress the error, {@link MessageStream#failed(Throwable)} to propagate
         * it, or a stream with a {@link CommandResultMessage} to substitute a result.
         * <p>
         * Accepts either a {@link CommandHandlingExceptionHandler} (which narrows the return type for substituting a
         * {@link CommandResultMessage}) or a generic {@link MessageHandlingExceptionHandler} typed at any supertype of
         * {@link CommandMessage}, allowing a single handler implementation (such as a logger) to be reused across
         * command, event, and query phases.
         * <p>
         * Multiple calls accumulate handlers; later-registered handlers are applied closer to the handler and see
         * exceptions first.
         *
         * @param handlerBuilder builder for the exception handler to apply to the command handling component
         * @return this phase for further configuration
         */
        default CommandHandlerPhase withExceptionHandler(
                ComponentBuilder<? extends MessageHandlingExceptionHandler<? super CommandMessage>> handlerBuilder) {
            requireNonNull(handlerBuilder, "The exception handler builder must not be null.");
            return intercepted(c -> {
                MessageHandlingExceptionHandler<? super CommandMessage> handler = handlerBuilder.build(c);
                return (message, context, chain) ->
                        chain.proceed(message, context)
                             .cast()
                             .onErrorContinue(error -> {
                                 try {
                                     return handler.handle(message, context, error);
                                 } catch (Exception e) {
                                     return MessageStream.failed(e);
                                 }
                             });
            });
        }
    }
}

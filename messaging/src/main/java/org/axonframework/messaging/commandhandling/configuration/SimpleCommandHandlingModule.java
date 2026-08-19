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

import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandHandlingComponent;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.SimpleCommandHandlingComponent;
import org.axonframework.messaging.commandhandling.interception.InterceptingCommandHandlingComponent;
import org.axonframework.common.FutureUtils;
import org.axonframework.common.configuration.BaseModule;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.QualifiedName;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;



import static java.util.Objects.requireNonNull;
import static org.axonframework.common.configuration.ComponentDefinition.ofTypeAndName;

/**
 * Simple implementation of the {@link CommandHandlingModule}.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @since 5.0.0
 */
class SimpleCommandHandlingModule extends BaseModule<SimpleCommandHandlingModule>
        implements CommandHandlingModule,
        CommandHandlingModule.SetupPhase,
        CommandHandlingModule.CommandHandlerPhase {

    private final String commandHandlingComponentName;
    private final Map<QualifiedName, ComponentBuilder<CommandHandler>> handlerBuilders;
    private final List<ComponentBuilder<CommandHandlingComponent>> handlingComponentBuilders;
    private final List<ComponentBuilder<MessageHandlerInterceptor<? super CommandMessage>>> interceptorBuilders;

    SimpleCommandHandlingModule(String moduleName) {
        super(requireNonNull(moduleName, "The module name cannot be null."));
        this.commandHandlingComponentName = "CommandHandlingComponent[" + moduleName + "]";
        this.handlerBuilders = new HashMap<>();
        this.handlingComponentBuilders = new ArrayList<>();
        this.interceptorBuilders = new ArrayList<>();
    }

    @Override
    public CommandHandlerPhase commandHandlers() {
        return this;
    }

    @Override
    public CommandHandlerPhase commandHandler(QualifiedName commandName,
                                              ComponentBuilder<CommandHandler> commandHandlerBuilder) {
        handlerBuilders.put(requireNonNull(commandName, "The command name cannot be null."),
                            requireNonNull(commandHandlerBuilder, "The command handler builder cannot be null."));
        return this;
    }

    @Override
    public CommandHandlerPhase commandHandlingComponent(
            ComponentBuilder<CommandHandlingComponent> handlingComponentBuilder
    ) {
        handlingComponentBuilders.add(
                requireNonNull(handlingComponentBuilder, "The command handling component builder cannot be null.")
        );
        return this;
    }

    @Override
    public CommandHandlerPhase intercepted(
            ComponentBuilder<MessageHandlerInterceptor<? super CommandMessage>> interceptorBuilder
    ) {
        interceptorBuilders.add(requireNonNull(interceptorBuilder, "interceptorBuilder must not be null"));
        return this;
    }

    @Override
    public CommandHandlingModule build() {
        registerCommandHandlingComponent();
        return this;
    }

    private void registerCommandHandlingComponent() {
        componentRegistry(cr -> cr.registerComponent(commandHandlingComponentComponentDefinition()));
    }

    private ComponentDefinition<CommandHandlingComponent> commandHandlingComponentComponentDefinition() {
        return ofTypeAndName(CommandHandlingComponent.class, commandHandlingComponentName)
                .withBuilder(c -> {
                    SimpleCommandHandlingComponent commandHandlingComponent = SimpleCommandHandlingComponent.create(
                            commandHandlingComponentName
                    );
                    handlingComponentBuilders.forEach(handlingComponent -> commandHandlingComponent.subscribe(
                            handlingComponent.build(c)));
                    handlerBuilders.forEach((key, value) -> commandHandlingComponent.subscribe(key, value.build(c)));
                    CommandHandlingComponent result = commandHandlingComponent;
                    if (!interceptorBuilders.isEmpty()) {
                        result = new InterceptingCommandHandlingComponent(
                                interceptorBuilders.stream().map(b -> b.build(c)).collect(Collectors.toList()),
                                result
                        );
                    }
                    return result;
                })
                .onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, (configuration, component) -> {
                    configuration.getComponent(CommandBus.class)
                                 .subscribe(configuration.getComponent(CommandHandlingComponent.class,
                                                                       commandHandlingComponentName));
                    return FutureUtils.emptyCompletedFuture();
                });
    }
}

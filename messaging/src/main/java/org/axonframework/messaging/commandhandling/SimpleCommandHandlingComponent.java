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

package org.axonframework.messaging.commandhandling;

import org.axonframework.common.Assert;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.infra.DescribableComponent;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * A simple implementation of the {@link CommandHandlingComponent} interface, allowing for easy registration of
 * {@link CommandHandler CommandHandlers} and other {@link CommandHandlingComponent CommandHandlingComponents}.
 * <p>
 * Registered subcomponents are preferred over registered command handlers when handling a command.
 *
 * @author Allard Buijze
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @author Mateusz Nowak
 * @since 5.0.0
 */
public class SimpleCommandHandlingComponent implements CommandHandlingComponent,
        CommandHandlerRegistry<SimpleCommandHandlingComponent> {

    private final String name;
    private final Map<QualifiedName, List<CommandHandler>> commandHandlers = new HashMap<>();
    private final Set<CommandHandlingComponent> subComponents = new HashSet<>();

    /**
     * Instantiates a simple {@link CommandHandlingComponent} that is able to handle commands and delegate them to
     * subcomponents.
     *
     * @param name The name of the component, used for {@link DescribableComponent describing} the component.
     * @return A simple {@link CommandHandlingComponent} instance with the given {@code name}.
     */
    public static SimpleCommandHandlingComponent create(String name) {
        return new SimpleCommandHandlingComponent(name);
    }

    private SimpleCommandHandlingComponent(String name) {
        this.name = Assert.nonEmpty(name, "The name may not be null or empty.");
    }

    @Override
    public SimpleCommandHandlingComponent subscribe(QualifiedName name,
                                                    CommandHandler commandHandler) {
        if (commandHandler instanceof CommandHandlingComponent component) {
            return subscribe(component);
        }

        commandHandlers.compute(name, (key, existingHandlers) -> {
            if (existingHandlers == null) {
                existingHandlers = new ArrayList<>();
            }
            for (CommandHandler existing : existingHandlers) {
                if (existing == commandHandler) {
                    return existingHandlers;
                }
                if (existing.supportedVersions().overlaps(commandHandler.supportedVersions())) {
                    throw new DuplicateCommandHandlerSubscriptionException(name, existing, commandHandler);
                }
            }
            existingHandlers.add(commandHandler);
            return existingHandlers;
        });

        return this;
    }

    @Override
    public SimpleCommandHandlingComponent subscribe(CommandHandlingComponent commandHandlingComponent) {
        subComponents.add(commandHandlingComponent);
        return this;
    }

    @Override
    public MessageStream.Single<CommandResultMessage> handle(CommandMessage command,
                                                             ProcessingContext context) {
        QualifiedName qualifiedName = requireNonNull(command, "The command message cannot be null.")
                .type()
                .qualifiedName();
        Optional<CommandHandlingComponent> optionalSubHandler =
                subComponents.stream()
                             .filter(subComponent -> subComponent.supportedCommands().contains(qualifiedName))
                             .findFirst();

        if (optionalSubHandler.isPresent()) {
            try {
                return optionalSubHandler.get().handle(command, context);
            } catch (Throwable e) {
                return MessageStream.failed(e);
            }
        }

        String version = command.type().version();
        List<CommandHandler> handlers = commandHandlers.get(qualifiedName);
        if (handlers != null) {
            Optional<MessageStream.Single<CommandResultMessage>> result = handleWithVersionMatch(handlers, version, command, context);
            if (result.isPresent()) {
                return result.get();
            }
        }

        String message = "No handler was subscribed for command with qualified name [%s] on component [%s]. Registered handlers: [%s]"
                .formatted(qualifiedName.fullName(), this.getClass().getName(), supportedCommands());
        return MessageStream.failed(new NoHandlerForCommandException(message));
    }

    private Optional<MessageStream.Single<CommandResultMessage>> handleWithVersionMatch(
            List<CommandHandler> handlers,
            String version,
            CommandMessage command,
            ProcessingContext context) {
        for (CommandHandler handler : handlers) {
            if (handler.supportedVersions().matches(version)) {
                try {
                    return Optional.of(handler.handle(command, context));
                } catch (Throwable e) {
                    return Optional.of(MessageStream.failed(e));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("name", name);
        descriptor.describeProperty("commandHandlers", commandHandlers);
        descriptor.describeProperty("subComponents", subComponents);
    }

    @Override
    public Set<QualifiedName> supportedCommands() {
        var combinedNames = new HashSet<>(commandHandlers.keySet());
        subComponents.forEach(subComponent -> combinedNames.addAll(subComponent.supportedCommands()));
        return combinedNames;
    }
}

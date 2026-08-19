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

package org.axonframework.examples.demo.multitenancy.university.write.enrollstudent;

import org.axonframework.common.configuration.Module;
import org.axonframework.common.configuration.ModuleBuilder;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.examples.demo.multitenancy.shared.DemoBacking;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;

import java.util.Objects;

/**
 * Registers the enroll-student write slice. The declarative demo passes its {@link EventSourcingConfigurer}
 * to {@link #configure(EventSourcingConfigurer)}, and the Spring Boot demo exposes {@link #entityModule()}
 * and {@link #commandModule()} as {@link Module} beans the starter picks up.
 */
public final class EnrollStudentConfiguration {

    private EnrollStudentConfiguration() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Registers the slice's entity and command handler on the given {@code configurer}.
     *
     * @param configurer the event sourcing configurer to register the slice on
     * @param backing    what backs this run
     * @return the given {@code configurer}, for further configuring
     */
    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer,
                                                    DemoBacking backing) {
        return configurer.registerEntity(entityModuleBuilder())
                         .registerCommandHandlingModule(commandModuleBuilder(backing));
    }

    /**
     * The slice's event-sourced entity as a {@link Module}, for wiring styles that register modules as
     * beans, such as Spring Boot.
     *
     * @return the entity module
     */
    public static Module entityModule() {
        return entityModuleBuilder();
    }

    /**
     * The slice's command handling module, for wiring styles that register modules as beans, such as
     * Spring Boot.
     *
     * @param backing    what backs this run
     * @return the command handling module
     */
    public static Module commandModule(DemoBacking backing) {
        return commandModuleBuilder(backing).build();
    }

    /**
     * The name the framework stores and loads this slice's course snapshots under, resolved through the
     * given {@code messageTypeResolver}. The course entity stays private to this slice, so its snapshot
     * name is exposed here rather than the entity type itself.
     * <p>
     * This assumes the entity's repository resolves the name through the same resolver, which holds as long
     * as the entity module inherits the application's {@code MessageTypeResolver}.
     *
     * @param messageTypeResolver the resolver deriving the message type of this slice's course entity
     * @return the qualified name of this slice's course snapshots
     */
    public static QualifiedName courseSnapshotName(MessageTypeResolver messageTypeResolver) {
        Objects.requireNonNull(messageTypeResolver, "The message type resolver must not be null");
        return messageTypeResolver.resolveOrThrow(EnrollStudentCommandHandler.State.class).qualifiedName();
    }

    private static EventSourcedEntityModule<String, EnrollStudentCommandHandler.State> entityModuleBuilder() {
        return EventSourcedEntityModule.autodetected(String.class, EnrollStudentCommandHandler.State.class);
    }

    private static ModuleBuilder<CommandHandlingModule> commandModuleBuilder(DemoBacking backing) {
        return CommandHandlingModule.named("EnrollStudent")
                                    .commandHandlers()
                                    .autodetectedCommandHandlingComponent(
                                            config -> new EnrollStudentCommandHandler(backing));
    }
}

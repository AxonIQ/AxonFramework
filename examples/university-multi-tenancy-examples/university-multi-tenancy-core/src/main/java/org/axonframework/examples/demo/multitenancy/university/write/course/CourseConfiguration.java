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

package org.axonframework.examples.demo.multitenancy.university.write.course;

import org.axonframework.common.configuration.Module;
import org.axonframework.common.configuration.ModuleBuilder;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;

/**
 * Registers the event-sourced course write side, shared by both runnable demos so they wire the identical
 * entity and command handler two ways. The declarative demo passes its {@code EventSourcingConfigurer} to
 * {@link #configure(EventSourcingConfigurer)}, and the Spring Boot demo exposes {@link #entityModule()}
 * and {@link #commandModule()} as {@link Module} beans the starter picks up.
 * <p>
 * The registration says nothing about tenants. Multi-tenancy makes the one event store it runs against
 * tenant-aware, so the same entity and handler transparently source and append per tenant.
 */
public final class CourseConfiguration {

    private CourseConfiguration() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Registers the course entity and its command handling module on the given {@code configurer}, for the
     * declarative Configuration API.
     *
     * @param configurer the event sourcing configurer to register the course write side on
     * @return the given {@code configurer}, for further configuring
     */
    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        return configurer.registerEntity(courseEntityModule())
                         .registerCommandHandlingModule(courseCommandModule());
    }

    /**
     * The event-sourced course entity as a {@link Module}, for wiring styles that register modules as
     * beans, such as Spring Boot.
     *
     * @return the course entity module
     */
    public static Module entityModule() {
        return courseEntityModule();
    }

    /**
     * The course command handling module, for wiring styles that register modules as beans, such as
     * Spring Boot.
     *
     * @return the course command handling module
     */
    public static Module commandModule() {
        return courseCommandModule().build();
    }

    // The private variants keep the precise EventSourcedEntityModule / ModuleBuilder types that
    // configure(...) needs, while the public methods above widen to Module for registration as beans.
    private static EventSourcedEntityModule<String, Course> courseEntityModule() {
        return EventSourcedEntityModule.autodetected(String.class, Course.class);
    }

    private static ModuleBuilder<CommandHandlingModule> courseCommandModule() {
        return CommandHandlingModule.named("course")
                                    .commandHandlers()
                                    .autodetectedCommandHandlingComponent(config -> new CourseCommandHandler());
    }
}

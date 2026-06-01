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

package org.axonframework.examples.demo.coursecatalog.catalog.write.enrollstudent;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;

/** Wires the enroll-student slice into an {@link EventSourcingConfigurer}. */
public final class EnrollStudentConfiguration {

    private EnrollStudentConfiguration() {
    }

    /**
     * @param configurer the configurer to extend
     * @return the configurer with the slice registered
     */
    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        var stateEntity = EventSourcedEntityModule
                .autodetected(EnrolmentId.class, EnrollStudentCommandHandler.State.class);

        var commandHandling = CommandHandlingModule
                .named("EnrollStudent")
                .commandHandlers()
                .autodetectedCommandHandlingComponent(c -> new EnrollStudentCommandHandler());

        return configurer
                .registerEntity(stateEntity)
                .registerCommandHandlingModule(commandHandling);
    }
}

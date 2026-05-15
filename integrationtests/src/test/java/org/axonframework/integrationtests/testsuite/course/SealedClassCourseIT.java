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

package org.axonframework.integrationtests.testsuite.course;

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.integrationtests.testsuite.AbstractIT;
import org.axonframework.integrationtests.testsuite.course.commands.CreateCourse;
import org.axonframework.integrationtests.testsuite.course.commands.PublishCourse;
import org.axonframework.integrationtests.testsuite.course.module.SealedClassCourseCommandHandlers;
import org.axonframework.integrationtests.testsuite.course.module.SealedClassCourseCommandHandlers.CourseState.PublishedCourse;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.modelling.StateManager;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

public abstract class SealedClassCourseIT extends AbstractIT {

    protected UnitOfWorkFactory unitOfWorkFactory;

    @Override
    protected ApplicationConfigurer applicationConfigurer() {
        // configuration example see https://github.com/holixon/emn-kotlin/blob/8de05d6f3601df5de3d17e23066b2c7cef836d86/examples/university/src/main/kotlin/faculty/write/renamecoursepolymorph/RenameCoursePolymorphConfiguration.kt
        final var configurer = EventSourcingConfigurer.create();
        final var courseEntity = EventSourcedEntityModule.autodetected(String.class,
                                                                       SealedClassCourseCommandHandlers.CourseState.class);

        final var commandHandlingModule = CommandHandlingModule.named("SealedClassCommandHandlers")
                                                               .commandHandlers()
                                                               .autodetectedCommandHandlingComponent(c -> new SealedClassCourseCommandHandlers())
                                                               .build();

        return configurer.registerEntity(courseEntity)
                         .registerCommandHandlingModule(commandHandlingModule);
    }

    @BeforeEach
    void doStartApp() {
        super.startApp();
        unitOfWorkFactory = startedConfiguration.getComponent(UnitOfWorkFactory.class);
    }

    @Test
    void canCreateAndPublishCourseForPolymorphicEntity() {
        // prepare
        String courseId = "63f680bf-db76-47f0-b62e-2a89e046e466";

        // test
        sendCommand(new CreateCourse(courseId));
        sendCommand(new PublishCourse(courseId));

        // assert
        UnitOfWork uow = unitOfWorkFactory.create();
        assertTrue(uow.executeWithResult(ctx -> ctx.component(StateManager.class)
                                                   .repository(SealedClassCourseCommandHandlers.CourseState.class,
                                                               String.class)
                                                   .load(courseId, ctx)
                                                   .thenApply(cs ->
                                                                      cs.entity() instanceof PublishedCourse)
                      )
                      .join(), "Expected state to be instance of PublishedCourse");
    }

    private void sendCommand(Object command) {
        commandGateway.send(command).getResultMessage().join();
    }
}

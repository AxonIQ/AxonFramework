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

package org.axonframework.integrationtests.testsuite.administration;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.integrationtests.testsuite.administration.commands.AssignTaskCommand;
import org.axonframework.integrationtests.testsuite.administration.commands.ChangeEmailAddress;
import org.axonframework.integrationtests.testsuite.administration.commands.CompleteTaskCommand;
import org.axonframework.integrationtests.testsuite.administration.commands.CreateEmployee;
import org.axonframework.integrationtests.testsuite.administration.common.PersonIdentifier;
import org.axonframework.integrationtests.testsuite.administration.common.PersonType;
import org.axonframework.integrationtests.testsuite.administration.state.mutable.MutablePerson;
import org.axonframework.integrationtests.testsuite.administration.state.mutable.MutableTask;
import org.axonframework.modelling.entity.EntityMetamodel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the administration test suite using as many reflection components of the {@link EntityMetamodel} and related
 * classes as possible. As reflection-based components are added, this test may change to use more of them.
 */
public abstract class MutableReflectionEntityModelAdministrationIT extends AbstractAdministrationIT {

    @Test
    void appendCriteriaBuilderOnTheRootEntityCoversItsSubclassesButNotOtherEntities() {
        // given MutablePerson declares the only builder in its hierarchy, and MutableTask declares its own
        PersonIdentifier identifier = new PersonIdentifier(PersonType.EMPLOYEE, createId("builder-employee"));
        MutablePerson.resetAppendCriteriaCommands();
        MutableTask.resetAppendCriteriaCommands();

        // when commands declared by both MutablePerson and MutableEmployee are handled
        commandGateway.send(new CreateEmployee(identifier, "person@axon.test", "Developer", 1000.0))
                      .getResultMessage().join();
        commandGateway.send(new ChangeEmailAddress(identifier, "updated@axon.test"))
                      .getResultMessage().join();
        commandGateway.send(new AssignTaskCommand(identifier, "task", "Verify ownership"))
                      .getResultMessage().join();
        commandGateway.send(new CompleteTaskCommand(identifier, "task"))
                      .getResultMessage().join();

        // then the inherited builder covers the subclass' commands too, without MutableEmployee redeclaring it
        assertThat(MutablePerson.appendCriteriaCommands())
                .containsExactly(CreateEmployee.class, ChangeEmailAddress.class, AssignTaskCommand.class);
        // while a separate child entity keeps its own builder
        assertThat(MutableTask.appendCriteriaCommands()).containsExactly(CompleteTaskCommand.class);
    }

    @Override
    protected EventSourcingConfigurer testSuiteConfigurer(EventSourcingConfigurer configurer) {
        var personEntity = EventSourcedEntityModule.autodetected(PersonIdentifier.class, MutablePerson.class);
        return configurer.componentRegistry(cr -> cr.registerModule(personEntity));
    }

}

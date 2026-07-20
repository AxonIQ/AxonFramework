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

package org.axonframework.integrationtests.testsuite.administration.state.immutable;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.integrationtests.testsuite.administration.commands.AssignTaskCommand;
import org.axonframework.integrationtests.testsuite.administration.commands.CreateEmployee;
import org.axonframework.integrationtests.testsuite.administration.commands.GrantCertificationCommand;
import org.axonframework.integrationtests.testsuite.administration.common.PersonIdentifier;
import org.axonframework.integrationtests.testsuite.administration.events.CertificationGranted;
import org.axonframework.integrationtests.testsuite.administration.events.EmailAddressChanged;
import org.axonframework.integrationtests.testsuite.administration.events.EmployeeCreated;
import org.axonframework.integrationtests.testsuite.administration.events.TaskAssigned;
import org.axonframework.modelling.entity.annotation.EntityMember;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record ImmutableEmployee(
        PersonIdentifier identifier,
        String emailAddress,
        @EntityMember
        ImmutableSalaryInformation salaryInformation,
        @EntityMember(routingKey = "taskId")
        List<ImmutableTask> taskList,
        @EntityMember(routingKey = "certificationName")
        Map<String, ImmutableCertification> certifications
) implements ImmutablePerson {

    @EntityCreator
    public ImmutableEmployee(EmployeeCreated employeeCreated) {
        this(employeeCreated.identifier(),
             employeeCreated.emailAddress(),
             new ImmutableSalaryInformation(employeeCreated.initialSalary(), employeeCreated.role()),
             new ArrayList<>(),
             new HashMap<>()
        );
    }

    @CommandHandler
    public static void handle(CreateEmployee command, EventAppender eventAppender) {
        eventAppender.append(new EmployeeCreated(
                command.identifier(),
                command.emailAddress(),
                command.role(),
                command.initialSalary()
        ));
    }

    @CommandHandler
    public void handle(AssignTaskCommand command, EventAppender eventAppender) {
        if (taskList.stream().filter(s -> !s.isCompleted()).collect(Collectors.toSet()).size() >= 3) {
            throw new IllegalStateException("Cannot assign more than 3 tasks to an employee");
        }
        eventAppender.append(new TaskAssigned(
                command.identifier(),
                command.id(),
                command.description()
        ));
    }

    @CommandHandler
    public void handle(GrantCertificationCommand command, EventAppender eventAppender) {
        if (certifications.containsKey(command.certificationName())) {
            throw new IllegalStateException("Employee already holds certification " + command.certificationName());
        }
        eventAppender.append(new CertificationGranted(
                command.identifier(),
                command.certificationName(),
                command.issuingBody()
        ));
    }

    @EventSourcingHandler
    public ImmutableEmployee on(EmployeeCreated event) {
        return new ImmutableEmployee(
                event.identifier(),
                event.emailAddress(),
                new ImmutableSalaryInformation(event.initialSalary(), event.role()),
                new ArrayList<>(),
                new HashMap<>()
        );
    }

    @EventSourcingHandler
    public ImmutableEmployee on(TaskAssigned event) {
        List<ImmutableTask> newTaskList = new ArrayList<>(taskList);
        newTaskList.add(new ImmutableTask(event.taskId(), false));
        return new ImmutableEmployee(
                identifier,
                emailAddress,
                salaryInformation,
                newTaskList,
                certifications
        );
    }

    @EventSourcingHandler
    public ImmutableEmployee on(CertificationGranted event) {
        Map<String, ImmutableCertification> newCertifications = new HashMap<>(certifications);
        newCertifications.put(
                event.certificationName(),
                new ImmutableCertification(event.certificationName(), event.issuingBody(), false)
        );
        return new ImmutableEmployee(
                identifier,
                emailAddress,
                salaryInformation,
                taskList,
                newCertifications
        );
    }

    public List<ImmutableTask> getTaskList() {
        return taskList;
    }

    public ImmutableEmployee evolveTaskList(List<ImmutableTask> taskList) {
        return new ImmutableEmployee(
                identifier,
                emailAddress,
                salaryInformation,
                taskList,
                certifications
        );
    }

    public Map<String, ImmutableCertification> getCertifications() {
        return certifications;
    }

    public ImmutableEmployee evolveCertifications(Map<String, ImmutableCertification> certifications) {
        return new ImmutableEmployee(
                identifier,
                emailAddress,
                salaryInformation,
                taskList,
                certifications
        );
    }

    @Override
    public ImmutableEmployee on(EmailAddressChanged event) {
        return new ImmutableEmployee(
                identifier,
                event.emailAddress(),
                salaryInformation,
                taskList,
                certifications
        );
    }

    public ImmutableEmployee evolveSalaryInformation(ImmutableSalaryInformation immutableSalaryInformation) {
        return new ImmutableEmployee(
                identifier,
                emailAddress,
                immutableSalaryInformation,
                taskList,
                certifications
        );
    }
}

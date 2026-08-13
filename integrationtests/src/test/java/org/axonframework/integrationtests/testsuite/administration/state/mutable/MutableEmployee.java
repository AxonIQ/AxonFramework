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

package org.axonframework.integrationtests.testsuite.administration.state.mutable;

import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.AppendCriteriaBuilder;
import org.axonframework.integrationtests.testsuite.administration.commands.AssignTaskCommand;
import org.axonframework.integrationtests.testsuite.administration.commands.CreateEmployee;
import org.axonframework.integrationtests.testsuite.administration.commands.GrantCertificationCommand;
import org.axonframework.integrationtests.testsuite.administration.events.CertificationGranted;
import org.axonframework.integrationtests.testsuite.administration.events.EmployeeCreated;
import org.axonframework.integrationtests.testsuite.administration.events.TaskAssigned;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.entity.annotation.EntityMember;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.CopyOnWriteArrayList;

public class MutableEmployee extends MutablePerson {

    private static final List<Class<?>> appendCriteriaCommands = new CopyOnWriteArrayList<>();

    @EntityMember
    private MutableSalaryInformation salary;
    @EntityMember(routingKey = "taskId")
    private List<MutableTask> taskList = new ArrayList<>();
    @EntityMember(routingKey = "certificationName")
    private Map<String, MutableCertification> certifications = new HashMap<>();

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
    public void on(EmployeeCreated event) {
        this.identifier = event.identifier();
        this.emailAddress = event.emailAddress();
        this.salary = new MutableSalaryInformation(event.initialSalary(), event.role());
    }

    @EventSourcingHandler
    public void on(TaskAssigned event) {
        taskList.add(new MutableTask(event.taskId()));
    }

    @EventSourcingHandler
    public void on(CertificationGranted event) {
        certifications.put(
                event.certificationName(),
                new MutableCertification(event.certificationName(), event.issuingBody())
        );
    }

    public List<MutableTask> getTaskList() {
        return taskList;
    }

    public void setTaskList(List<MutableTask> taskList) {
        this.taskList = taskList;
    }

    public Map<String, MutableCertification> getCertifications() {
        return certifications;
    }

    public void setCertifications(Map<String, MutableCertification> certifications) {
        this.certifications = certifications;
    }

    @AppendCriteriaBuilder
    static EventCriteria appendCriteria(CommandMessage command, EventCriteria sourcingCriteria) {
        appendCriteriaCommands.add(command.payloadType());
        return sourcingCriteria;
    }

    public static void resetAppendCriteriaCommands() {
        appendCriteriaCommands.clear();
    }

    public static List<Class<?>> appendCriteriaCommands() {
        return List.copyOf(appendCriteriaCommands);
    }
}

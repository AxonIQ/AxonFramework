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

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.integrationtests.testsuite.AbstractIT;
import org.axonframework.integrationtests.testsuite.administration.commands.AssignTaskCommand;
import org.axonframework.integrationtests.testsuite.administration.commands.ChangeEmailAddress;
import org.axonframework.integrationtests.testsuite.administration.commands.CompleteTaskCommand;
import org.axonframework.integrationtests.testsuite.administration.commands.CreateCustomer;
import org.axonframework.integrationtests.testsuite.administration.commands.CreateEmployee;
import org.axonframework.integrationtests.testsuite.administration.commands.GiveRaise;
import org.axonframework.integrationtests.testsuite.administration.common.PersonIdentifier;
import org.axonframework.integrationtests.testsuite.administration.common.PersonType;
import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Test suite for verifying polymorphic behavior of entities. Can be implemented by different test classes that verify
 * different ways of building the {@link org.axonframework.modelling.entity.EntityCommandHandlingComponent}.
 */
public abstract class AbstractAdministrationIT extends AbstractIT {

    private final CreateEmployee CREATE_EMPLOYEE_1_COMMAND = new CreateEmployee(
            new PersonIdentifier(PersonType.EMPLOYEE, createId("employee")),
            "mitchell.herrijgers@axoniq.io",
            "Bug Creator",
            3000.0);

    private final CreateCustomer CREATE_CUSTOMER_1_COMMAND = new CreateCustomer(
            new PersonIdentifier(PersonType.CUSTOMER, createId("customer")),
            "homer@the-simpsons.io"
    );

    @BeforeEach
    public void doStartApp() {
        super.startApp();
    }

    @Override
    protected ApplicationConfigurer applicationConfigurer() {
        var configurer = EventSourcingConfigurer.create();
        return testSuiteConfigurer(configurer);
    }

    /**
     * Allows for further configuration of the {@link EventSourcingConfigurer} used in the test suite.
     * <p>
     * This method can be overridden by subclasses to add additional configuration.
     *
     * @param configurer The {@link EventSourcingConfigurer} to configure.
     * @return The configured {@link EventSourcingConfigurer}.
     */
    protected EventSourcingConfigurer testSuiteConfigurer(EventSourcingConfigurer configurer) {
        return configurer;
    }

    @Test
    void canNotCreateDuplicateEmployee() {
        sendCommand(CREATE_EMPLOYEE_1_COMMAND);

        assertThrowsExceptionWithText("AppendEventsTransactionRejectedException",
                                      () -> sendCommand(CREATE_EMPLOYEE_1_COMMAND));
    }

    @Test
    void canNotCreateDuplicateCustomer() {
        sendCommand(CREATE_CUSTOMER_1_COMMAND);

        assertThrowsExceptionWithText("AppendEventsTransactionRejectedException",
                                      () -> sendCommand(CREATE_CUSTOMER_1_COMMAND));
    }

    @Test
    void canOnlyUpdateEmailAddressToNewValueForEmployee() {
        sendCommand(CREATE_EMPLOYEE_1_COMMAND);

        assertThrowsExceptionWithText("Email address cannot be the same as the current one", () -> {
            sendCommand(new ChangeEmailAddress(CREATE_EMPLOYEE_1_COMMAND.identifier(),
                                               "mitchell.herrijgers@axoniq.io"));
        });

        // But we can update to a new value
        sendCommand(new ChangeEmailAddress(CREATE_EMPLOYEE_1_COMMAND.identifier(), "mitchell@axoniq.io"));
    }

    @Test
    void canOnlyUpdateEmailAddressToNewValueForCustomer() {
        sendCommand(CREATE_CUSTOMER_1_COMMAND);

        assertThrowsExceptionWithText("Email address cannot be the same as the current one", () -> {
            sendCommand(new ChangeEmailAddress(CREATE_CUSTOMER_1_COMMAND.identifier(),
                                               CREATE_CUSTOMER_1_COMMAND.emailAddress()));
        });

        // But we can update to a new value
        sendCommand(new ChangeEmailAddress(CREATE_CUSTOMER_1_COMMAND.identifier(), "dog@dog.y"));
    }

    @Test
    void canGiveRaiseToEmployee() {
        sendCommand(CREATE_EMPLOYEE_1_COMMAND);

        sendCommand(new GiveRaise(CREATE_EMPLOYEE_1_COMMAND.identifier(), 5000000.0));

        assertThrowsExceptionWithText("New salary must be greater than current salary", () -> {
            sendCommand(new GiveRaise(CREATE_EMPLOYEE_1_COMMAND.identifier(), 4000000.0));
        });
    }

    @Test
    void canAssignUpToThreeUncompletedTasksToEmployees() {
        sendCommand(CREATE_EMPLOYEE_1_COMMAND);

        for (int i = 0; i < 3; i++) {
            sendCommand(new AssignTaskCommand(CREATE_EMPLOYEE_1_COMMAND.identifier(),
                                              "task-" + i,
                                              "Task " + i));
        }

        assertThrowsExceptionWithText("Cannot assign more than 3 tasks to an employee", () -> {
            sendCommand(new AssignTaskCommand(CREATE_EMPLOYEE_1_COMMAND.identifier(), "task-4",
                                              "Task " + 4));
        });

        // Now, let's complete one
        sendCommand(new CompleteTaskCommand(CREATE_EMPLOYEE_1_COMMAND.identifier(), "task-0"));

        // And assign a new one
        sendCommand(new AssignTaskCommand(CREATE_EMPLOYEE_1_COMMAND.identifier(), "task-4", "Task " + 4));
    }


    private void assertThrowsExceptionWithText(String expectedMessage, Runnable runnable) {
        try {
            runnable.run();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            String matchTarget = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            Assertions.assertTrue(
                    matchTarget.toLowerCase().contains(expectedMessage.toLowerCase()),
                    () -> "Expected message to contain: " + expectedMessage
                            + ", but got: " + matchTarget + "\n"
                            + Arrays.stream(cause.getStackTrace())
                                    .map(StackTraceElement::toString)
                                    .collect(Collectors.joining("\n"))
            );
            return;
        } catch (Exception e) {
            Assertions.fail("Expected CompletionException, but got: " + e.getClass().getSimpleName());
        }
        Assertions.fail("Expected CompletionException, but got no exception");
    }

    private void sendCommand(Object command) {
        commandGateway.send(command).getResultMessage().join();
    }
}


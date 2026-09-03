/*
 * Copyright (c) 2010-2026. AxonIQ B.V.
 *
 * Licensed under the AXONIQ TERMS OF SERVICE,
 * Version 29 April 2026 (the "License");
 *
 * The software is available for evaluation use without registration.
 * Continued use beyond the evaluation period requires registration
 * and a commercial license. See the License for the specific language
 * governing permissions and limitations under the License.
 * You may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at:
 *  https://www.axoniq.io/legal/terms-of-service
 *
 * For licensing information and to register, visit:
 *  https://www.axoniq.io/pricing
 */
package org.axonframework.examples.workflow.simple.user;

import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContext;
import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContextFactory;
import io.axoniq.framework.workflow.runtime.api.execution.status.StepStatus;
import io.axoniq.framework.workflow.runtime.api.execution.status.WorkflowStatus;
import io.axoniq.framework.workflow.runtime.test.fixture.GivenWhen;
import io.axoniq.framework.workflow.runtime.test.fixture.Then;
import io.axoniq.framework.workflow.runtime.test.fixture.WorkflowTestFixture;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.Map;
import java.util.function.UnaryOperator;

import static io.axoniq.framework.workflow.runtime.test.fixture.WorkflowTestFixture.workflowModule;

/**
 * Demonstrates usage of own test phases.
 *
 * @author Simon Zambrovski
 */
public class UserSignupFixtureWithCustomPhasesTest {

    WorkflowTestFixture<CustomGivenWhen, CustomThen> fixture = WorkflowTestFixture.of(
            workflowModule(SimpleWorkflowContext.class,
            c -> new SimpleWorkflowContextFactory(),
            c -> new UserSignupWorkflow()),
            UnaryOperator.identity(),
            new CustomGivenWhen(),
            new CustomThen()
    );

    @Test
    void startsForVipAndActivatesUserAndSendsMailAndWaitsASecondAndCorrectMagicianFinishesWorkflow() {

        fixture.given()
               .publishEvent(new RegistrationReceivedEvent("2", "piggy@muppets.biz", "vip"))
               .customerRegistrationComplete()
               .timePasses(Duration.ofSeconds(1))
        ;

        fixture.when()
               .publishEvent(new MagicHappenedEvent("Merlin"))
        ;

        fixture.then()
               .noExecution()
               .workflowFinished(WorkflowStatus.COMPLETED)
               .stepsPassed("activateUser", "sendWelcomeEmail", "waitForMagicToHappen", "modifyPayload")
               .step("activateUser", StepStatus.COMPLETED)
               .payloadEquals(Map.of(
                       "magician", "Merlin",
                       "email", "piggy@muppets.biz",
                       "status", "vip",
                       "id", "2"
               ));
    }

    @Test
    void preservesFluentCustomPhases() {
        fixture.then()
               .customAssertion()
               .and()
               .given()
               .customAction()
               .then()
               .customAssertion()
               .and()
               .when()
               .customAction();
    }


    class CustomGivenWhen extends GivenWhen<CustomGivenWhen, CustomThen> {

        public CustomGivenWhen customAction() {
            return self();
        }

        public CustomGivenWhen customerRegistrationComplete() {
            return executionExists()
                    .execute("createUser")
                    .executeReturning("activateUser", Map.of())
                    .executeReturning("sendWelcomeEmail", Map.of());
        }
    }

    class CustomThen extends Then<CustomThen, CustomGivenWhen> {

        public CustomThen customAssertion() {
            return self();
        }
    }
}

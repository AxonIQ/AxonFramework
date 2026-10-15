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
import io.axoniq.framework.workflow.dsl.api.StepStatus;
import io.axoniq.framework.workflow.dsl.api.WorkflowStatus;
import io.axoniq.framework.workflow.runtime.test.fixture.WorkflowTestFixture;
import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.function.UnaryOperator;

import static io.axoniq.framework.workflow.runtime.test.fixture.WorkflowTestFixture.workflowModule;

/**
 * Fixture-based test for {@link UserSignupWorkflow}.
 *
 * @author Simon Zambrovski
 */
public class UserSignupFixtureTest {

    WorkflowTestFixture<?, ?> fixture = WorkflowTestFixture.of(
            workflowModule(SimpleWorkflowContext.class,
                           c -> new SimpleWorkflowContextFactory(),
                           c -> new UserSignupWorkflow()),
            UnaryOperator.identity()
    );

    @Test
    void dontStartForNonVip() {
        fixture
                .given()
                .noExecution();

        fixture.when()
               .publishEvent(new RegistrationReceivedEvent("1", "kermit@muppets.biz", "regular"));

        fixture.then()
               .noExecution()
               .noHistory();
    }

    @Test
    void startsForVip() {

        fixture.given()
               .noExecution()
        ;

        fixture.when()
               .publishEvent(new RegistrationReceivedEvent("2", "piggy@muppets.biz", "vip"))
        ;

        fixture.then()
               .executionExists()
               .waitingIn("createUser")
               .payloadEquals(Map.of(
                       "email", "piggy@muppets.biz",
                       "id", "2",
                       "status", "vip"
               ))
        ;
    }


    @Test
    void startsForVipAndActivatesUser() {

        fixture.given()
               .publishEvent(new RegistrationReceivedEvent("2", "piggy@muppets.biz", "vip"))
               .executionExists()
               .execute("createUser");

        fixture.when()
               .executeReturning("activateUser", Map.of());


        fixture.then()
               .executionExists()
               .waitingIn("sendWelcomeEmail")
        ;
    }

    @Test
    void startsForVipAndActivatesUserAndSendsMail() {

        fixture.given()
               .publishEvent(new RegistrationReceivedEvent("2", "piggy@muppets.biz", "vip"))
               .executionExists()
               .execute("createUser")
               .executeReturning("activateUser", Map.of());

        fixture.when()
               .executeReturning("sendWelcomeEmail", Map.of());


        fixture.then()
               .executionExists()
               .waitingIn("waitForEmailConfirmation")
        ;
    }

    @Test
    void startsForVipAndActivatesUserAndSendsMailAndOtherUsersEmailConfirmationTriggersNoProgress() {

        fixture.given()
               .publishEvent(new RegistrationReceivedEvent("2", "piggy@muppets.biz", "vip"))
               .executionExists()
               .execute("createUser")
               .executeReturning("activateUser", Map.of())
               .executeReturning("sendWelcomeEmail", Map.of())
        ;

        fixture.when()
               .publishEvent(new EmailConfirmedEvent("3"))
        ;

        fixture.then()
               .executionExists()
               .waitingIn("waitForEmailConfirmation");
    }

    @Test
    void startsForVipAndActivatesUserAndSendsMailAndEmailConfirmationFinishesWorkflow() {

        fixture.given()
               .publishEvent(new RegistrationReceivedEvent("2", "piggy@muppets.biz", "vip"))
               .executionExists()
               .execute("createUser")
               .executeReturning("activateUser", Map.of())
               .executeReturning("sendWelcomeEmail", Map.of())
        ;

        fixture.when()
               .publishEvent(new EmailConfirmedEvent("2"))
        ;

        fixture.then()
               .noExecution()
               .workflowFinished(WorkflowStatus.COMPLETED)
               .stepsPassed("activateUser", "sendWelcomeEmail", "waitForEmailConfirmation")
               .step("activateUser", StepStatus.COMPLETED)
               .payloadEquals(Map.of(
                       "email", "piggy@muppets.biz",
                       "status", "vip",
                       "id", "2"
               ));
    }
}

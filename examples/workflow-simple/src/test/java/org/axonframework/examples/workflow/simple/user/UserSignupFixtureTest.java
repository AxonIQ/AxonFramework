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
import io.axoniq.framework.workflow.runtime.test.fixture.WorkflowTestFixture;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.Map;
import java.util.function.UnaryOperator;

import static io.axoniq.framework.workflow.runtime.test.fixture.WorkflowTestFixture.workflowModule;
import static org.assertj.core.api.Assertions.assertThat;

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
               .waitingIn("waitASecond")
        ;
    }

    @Test
    void startsForVipAndActivatesUserAndSendsMailAndWaitsASecond() {
        fixture.given()
               .publishEvent(new RegistrationReceivedEvent("2", "piggy@muppets.biz", "vip"))
               .executionExists()
               .execute("createUser")
               .executeReturning("activateUser", Map.of())
               .executeReturning("sendWelcomeEmail", Map.of())
        ;

        fixture.when()
               .timePasses(Duration.ofSeconds(1));

        fixture.then()
               .executionExists()
               .waitingIn("waitForMagicToHappen")
        ;
    }

    @Test
    void startsForVipAndActivatesUserAndSendsMailAndWaitsASecondAndWrongMagicianTriggersNoProgress() {

        fixture.given()
               .publishEvent(new RegistrationReceivedEvent("2", "piggy@muppets.biz", "vip"))
               .executionExists()
               .execute("createUser")
               .executeReturning("activateUser", Map.of())
               .executeReturning("sendWelcomeEmail", Map.of())
               .timePasses(Duration.ofSeconds(1))
        ;

        fixture.when()
               .publishEvent(new MagicHappenedEvent("Saruman"))
        ;

        fixture.then()
               .executionExists()
               .waitingIn("waitForMagicToHappen")
               .payloadSatisfies(p ->
                                         assertThat(p).doesNotContainKey("magician")
               );
        ;
    }

    @Test
    void startsForVipAndActivatesUserAndSendsMailAndWaitsASecondAndCorrectMagicianFinishesWorkflow() {

        fixture.given()
               .publishEvent(new RegistrationReceivedEvent("2", "piggy@muppets.biz", "vip"))
               .executionExists()
               .execute("createUser")
               .executeReturning("activateUser", Map.of())
               .executeReturning("sendWelcomeEmail", Map.of())
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
}

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
package org.axonframework.examples.workflow.simple.kotlin.user

import io.axoniq.framework.workflow.configuration.WorkflowModule.WorkflowDefinitionPhase.DetectionPhase
import io.axoniq.framework.workflow.configuration.WorkflowModule.WorkflowDefinitionPhase.FinalizedPhase
import io.axoniq.framework.workflow.dsl.kotlin.WorkflowKontext
import io.axoniq.framework.workflow.dsl.kotlin.WorkflowKontextFactory
import io.axoniq.framework.workflow.runtime.api.execution.context.EventConditions
import io.axoniq.framework.workflow.runtime.api.execution.status.WorkflowStatus
import io.axoniq.framework.workflow.runtime.execution.DefaultEventNameCustomizer.Builder.namespace
import io.axoniq.framework.workflow.runtime.execution.PayloadPropertyWorkflowIdProvider.fromPayloadAttribute
import io.axoniq.framework.workflow.runtime.test.AbstractWorkflowTestBase
import io.axoniq.framework.workflow.runtime.test.utils.DelayedPublisher
import org.junit.jupiter.api.Test
import java.util.function.Function

/**
 * User signup test using the declarative workflow API kotlin DSL.
 * @author Simon Zambrovski
 */
class UserSignupTest : AbstractWorkflowTestBase<WorkflowKontext>(
    WorkflowKontext::class.java,
    { WorkflowKontextFactory() }
) {

    override fun getDeclaredDefinition(): Function<DetectionPhase<WorkflowKontext>, FinalizedPhase<WorkflowKontext>> {
        return { d ->
            d.declarative { WorkflowKontext.from(UserSignupWorkflow()::execute) }
                .workflowName("User signup workflow in Kotlin")
                .on(EventConditions.fromType(RegistrationReceivedEvent::class.java))
                .customized { c, wc ->
                    wc.eventNameCustomizer(namespace("io.axoniq.dsl.wf"))
                        .workflowIdProvider(
                            fromPayloadAttribute(
                                c, "id"
                            ) { id: String? -> "signup-$id" }
                        )
                }
        }
    }


    @Test
    fun `register user`() {

        delayedPublisher.addSchedules(
            listOf<DelayedPublisher.Schedule>(
                DelayedPublisher.Schedule.ofMillis(
                    500,
                    RegistrationReceivedEvent("user-456", "kermit@muppets.biz")
                ),
                DelayedPublisher.Schedule.ofMillis(
                    5500,
                    MagicHappenedEvent("Merlin")
                )
            )
        )


        // Arm the publisher to start the delayed execution
        delayedPublisher.start()

        testDriver.executionExists()
        testDriver.noExecution()
        testDriver.historyMatches { it.state().workflowStatus() == WorkflowStatus.COMPLETED }
        testDriver.testingState().hasStepsInOrder(
            "createUser",
            "activateUser",
            "waitASecond",
            "sendWelcomeEmail",
            "waitForMagicToHappen"
        )
    }

}

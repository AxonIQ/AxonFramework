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
package org.axonframework.examples.workflow.simple.kotlin.feature

import org.axonframework.examples.workflow.simple.kotlin.user.RegistrationReceivedEvent
import io.axoniq.framework.workflow.configuration.WorkflowModule.WorkflowDefinitionPhase.DetectionPhase
import io.axoniq.framework.workflow.configuration.WorkflowModule.WorkflowDefinitionPhase.FinalizedPhase
import io.axoniq.framework.workflow.dsl.kotlin.WorkflowKontext
import io.axoniq.framework.workflow.dsl.kotlin.WorkflowKontextFactory
import io.axoniq.framework.workflow.runtime.api.execution.context.EventConditions
import io.axoniq.framework.workflow.runtime.api.execution.status.WorkflowStatus
import io.axoniq.framework.workflow.runtime.test.AbstractWorkflowTestBase
import io.axoniq.framework.workflow.runtime.test.utils.DelayedPublisher
import org.junit.jupiter.api.Test
import java.util.function.Function
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SleepKotlinTest : AbstractWorkflowTestBase<WorkflowKontext>(
    WorkflowKontext::class.java,
    { WorkflowKontextFactory() }
) {

    override fun getDeclaredDefinition(): Function<DetectionPhase<WorkflowKontext>, FinalizedPhase<WorkflowKontext>> {
        return Function { d ->
            d.declarative {
                WorkflowKontext.from {
                    val cooldown = waitForEvent("cooldown", EventConditions.never(), timeout = 500.milliseconds)
                    val work = execute("work", timeout = 5.seconds) { _, _ -> mapOf("completion" to true) }

                    anyMatch({ it.isCompleted }, cooldown, work).await()
                }
            }
                .workflowName("Sleep Kotlin Workflow")
                .on(EventConditions.fromType(RegistrationReceivedEvent::class.java))
                .notCustomized()
        }
    }

    @Test
    fun `complete workflow with sleep`() {
        delayedPublisher.addSchedules(
            listOf(
                DelayedPublisher.Schedule.ofMillis(
                    100,
                    RegistrationReceivedEvent("user-sleep-kt", "kt@test.com")
                )
            )
        )

        delayedPublisher.start()

        testDriver.historyMatches { it.state().workflowStatus() == WorkflowStatus.COMPLETED }
        testDriver.testingState().hasSteps("cooldown", "work")
    }
}

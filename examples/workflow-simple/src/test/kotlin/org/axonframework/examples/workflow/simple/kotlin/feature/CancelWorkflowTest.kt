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
import io.axoniq.framework.workflow.dsl.kotlin.Kontext
import io.axoniq.framework.workflow.dsl.kotlin.WorkflowKontext
import io.axoniq.framework.workflow.dsl.kotlin.WorkflowKontextFactory
import io.axoniq.framework.workflow.runtime.api.execution.context.EventConditions
import io.axoniq.framework.workflow.runtime.api.execution.context.retry.RetryPolicy
import io.axoniq.framework.workflow.runtime.api.execution.status.WorkflowStatus
import io.axoniq.framework.workflow.runtime.execution.DefaultEventNameCustomizer.Builder.namespace
import io.axoniq.framework.workflow.runtime.execution.PayloadPropertyWorkflowIdProvider.fromPayloadAttribute
import io.axoniq.framework.workflow.runtime.test.AbstractWorkflowTestBase
import io.axoniq.framework.workflow.runtime.test.utils.DelayedPublisher
import org.junit.jupiter.api.Test
import java.util.function.Function
import kotlin.time.Duration.Companion.minutes

/**
 * @author Stefan Dragisic
 */
class CancelWorkflowTest : AbstractWorkflowTestBase<WorkflowKontext>(
    WorkflowKontext::class.java,
    { WorkflowKontextFactory() }
) {

    override fun getDeclaredDefinition(): Function<DetectionPhase<WorkflowKontext>, FinalizedPhase<WorkflowKontext>> {
        val workflow = CancelWorkflow()
        return { d ->
            d.declarative { WorkflowKontext.from(workflow::execute) }
                .workflowName("Cancel workflow in Kotlin")
                .on(EventConditions.fromType(RegistrationReceivedEvent::class.java))
                .customized { c, wc ->
                    wc.eventNameCustomizer(namespace("io.axoniq.dsl.cancel")
                        .workflowBaseName("Workflow"))
                        .workflowIdProvider(
                            fromPayloadAttribute(
                                c, "id"
                            ) { id: String? -> "cancel-$id" }
                        )
                }
        }
    }

    @Test
    fun `workflow is cancelled`() {

        delayedPublisher.addSchedules(
            listOf(
                DelayedPublisher.Schedule.ofMillis(
                    500,
                    RegistrationReceivedEvent("user-789", "cancel@test.com")
                )
            )
        )

        delayedPublisher.start()

        testDriver.executionExists()
        testDriver.noExecution()
        testDriver.historyMatches { it.state().workflowStatus() == WorkflowStatus.CANCELLED }
        testDriver.testingState().hasStepsInAnyOrder("stepA", "stepB", "stepC")
    }

    private class CancelWorkflow {

        fun execute(kontext: Kontext) = with(kontext) {
            val fiveMinMs = 5.minutes.inWholeMilliseconds
            val stepA = execute(
                stepName = "stepA",
                timeout = 5.minutes,
                retryPolicy = RetryPolicy.NONE
            ) { _, _ -> Thread.sleep(fiveMinMs); mapOf() }
            val stepB = execute(
                stepName = "stepB",
                timeout = 5.minutes,
                retryPolicy = RetryPolicy.NONE
            ) { _, _ -> Thread.sleep(fiveMinMs); mapOf() }
            val stepC = execute(
                stepName = "stepC",
                timeout = 5.minutes,
                retryPolicy = RetryPolicy.NONE
            ) { _, _ -> Thread.sleep(fiveMinMs); mapOf() }

            allMatch({ it.isCompleted }, stepA, stepB, stepC)

            Thread.sleep(5_000)
            cancel()
        }
    }
}

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
import io.axoniq.framework.workflow.configuration.WorkflowConfigurer
import io.axoniq.framework.workflow.configuration.WorkflowModule.WorkflowDefinitionPhase.DetectionPhase
import io.axoniq.framework.workflow.configuration.WorkflowModule.WorkflowDefinitionPhase.FinalizedPhase
import io.axoniq.framework.workflow.dsl.kotlin.Kontext
import io.axoniq.framework.workflow.dsl.kotlin.WorkflowKontext
import io.axoniq.framework.workflow.dsl.kotlin.WorkflowKontextFactory
import io.axoniq.framework.workflow.runtime.api.execution.context.EventConditions
import io.axoniq.framework.workflow.runtime.api.execution.context.retry.RetryPolicy
import io.axoniq.framework.workflow.runtime.api.execution.status.WorkflowStatus
import io.axoniq.framework.workflow.runtime.test.AbstractWorkflowTestBase
import io.axoniq.framework.workflow.runtime.test.utils.DelayedPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function
import java.util.function.UnaryOperator
import kotlin.time.Duration.Companion.seconds

class RetryWorkflowTest : AbstractWorkflowTestBase<WorkflowKontext>(
    WorkflowKontext::class.java,
    { WorkflowKontextFactory() }
) {
    private lateinit var workflow: KotlinRetryWorkflow

    override fun configure(): UnaryOperator<WorkflowConfigurer> =
        super.configure().apply {
            workflow = KotlinRetryWorkflow()
        }

    override fun getDeclaredDefinition(): Function<DetectionPhase<WorkflowKontext>, FinalizedPhase<WorkflowKontext>> {
        return { d ->
            d.declarative({ c -> WorkflowKontext.from(workflow::execute) })
                .workflowName("Retry workflow in Kotlin")
                .on(EventConditions.fromType(RegistrationReceivedEvent::class.java))
                .customized { c, w -> w }
        }
    }

    @Test
    fun `workflow retries and succeeds`() {
        delayedPublisher.addSchedules(
            listOf(
                DelayedPublisher.Schedule.ofMillis(
                    100,
                    RegistrationReceivedEvent("user-retry", "retry@test.com")
                )
            )
        )

        delayedPublisher.start()

        testDriver.historyMatches { it.state().workflowStatus() == WorkflowStatus.COMPLETED }
        testDriver.noExecution()
        assertThat(workflow.retryAttempts.get()).isEqualTo(3)
        assertThat(workflow.asyncRetryAttempts.get()).isEqualTo(2)
    }

    private class KotlinRetryWorkflow {

        val retryAttempts = AtomicInteger(0)
        val asyncRetryAttempts = AtomicInteger(0)

        fun execute(kontext: Kontext) = with(kontext) {
            awaitExecute(
                stepName = "retryStep",
                timeout = 5.seconds,
                retryPolicy = RetryPolicy.maxRetries(3)
            ) { _, _ ->
                val attempt = retryAttempts.incrementAndGet()
                if (attempt < 3) {
                    throw RuntimeException("Retry attempt $attempt")
                }
                mapOf("status" to "success")
            }

            val result = execute(
                stepName = "asyncRetryStep",
                timeout = 5.seconds,
                retryPolicy = RetryPolicy.maxRetries(2)
            ) { _, _ ->
                val attempt = asyncRetryAttempts.incrementAndGet()
                if (attempt < 2) {
                    throw RuntimeException("Async retry attempt $attempt")
                }
                mapOf("status" to "success")
            }

            result.await()
        }
    }
}

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

import io.axoniq.framework.workflow.configuration.WorkflowModule.WorkflowDefinitionPhase.DetectionPhase
import io.axoniq.framework.workflow.configuration.WorkflowModule.WorkflowDefinitionPhase.FinalizedPhase
import io.axoniq.framework.workflow.dsl.kotlin.Kontext
import io.axoniq.framework.workflow.dsl.kotlin.WorkflowKontext
import io.axoniq.framework.workflow.dsl.kotlin.WorkflowKontextFactory
import io.axoniq.framework.workflow.runtime.api.execution.context.EventConditions
import io.axoniq.framework.workflow.runtime.api.execution.state.StepCancellationException
import io.axoniq.framework.workflow.runtime.api.execution.state.StepTimedOutException
import io.axoniq.framework.workflow.runtime.api.execution.status.StepStatus
import io.axoniq.framework.workflow.runtime.api.execution.status.WorkflowStatus
import io.axoniq.framework.workflow.runtime.execution.DefaultEventNameCustomizer.Builder.namespace
import io.axoniq.framework.workflow.runtime.execution.PayloadPropertyWorkflowIdProvider.fromPayloadAttribute
import io.axoniq.framework.workflow.runtime.execution.WorkflowCancellationService
import io.axoniq.framework.workflow.runtime.execution.WorkflowExecutionRepository
import io.axoniq.framework.workflow.runtime.test.AbstractWorkflowTestBase
import io.axoniq.framework.workflow.runtime.test.utils.DelayedPublisher
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.axonframework.messaging.eventhandling.annotation.Event
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.function.Function
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Kotlin parity for the Java surfacing behaviour: `Kontext.block { ... }` must rethrow a cancelled step as
 * [StepCancellationException] and a timed-out step as [StepTimedOutException], not swallow either. The workflow keeps
 * a control-flow marker per branch (`*Surfaced` runs only inside the catch, `*Swallowed` only if the exception did not
 * surface) so the test proves the exception was actually thrown.
 *
 * @author Stefan Dragisic
 */
class BlockSurfacesCancellationAndTimeoutTest : AbstractWorkflowTestBase<WorkflowKontext>(
    WorkflowKontext::class.java,
    { WorkflowKontextFactory() }
) {

    override fun getDeclaredDefinition(): Function<DetectionPhase<WorkflowKontext>, FinalizedPhase<WorkflowKontext>> {
        val workflow = BlockWorkflow()
        return { d ->
            d.declarative { WorkflowKontext.from(workflow::execute) }
                .workflowName("Block surfacing in Kotlin")
                .on(EventConditions.fromType(StartBlockEvent::class.java))
                .customized { c, wc ->
                    wc.eventNameCustomizer(namespace("io.axoniq.dsl.blocksurface").workflowBaseName("Workflow"))
                        .workflowIdProvider(fromPayloadAttribute(c, "id") { id: String? -> "block-$id" })
                }
        }
    }

    @Test
    fun `block surfaces timeout and cancellation`() {
        delayedPublisher.addSchedules(
            listOf(DelayedPublisher.Schedule.ofMillis(100, StartBlockEvent("1")))
        )
        delayedPublisher.start()

        val executionRepository = configuration.getComponent(WorkflowExecutionRepository::class.java)

        // The timed-out block has surfaced (via timeoutSurfaced) and the body now parks on the second block.
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val execution = executionRepository.findById("block-1")
            assertThat(execution).isPresent
            val state = execution.get().state()
            assertThat(state.containsStep("cancelledWait")).isTrue()
            assertThat(state.getStep("cancelledWait").status()).isEqualTo(StepStatus.STARTED)
        }

        configuration.getComponent(WorkflowCancellationService::class.java)
            .requestStepCancellation("block-1", "cancelledWait", StepCancellationException("cancelled externally"))
            .join()

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val history = workflowHistoryRepository.findById("block-1")
            assertThat(history).isPresent
            val state = history.get().state()
            assertThat(state.workflowStatus()).isEqualTo(WorkflowStatus.COMPLETED)
            // Timeout surfaced as StepTimedOutException: the catch ran, the fall-through did not.
            assertThat(state.getStep("timedOutWait").status()).isEqualTo(StepStatus.TIMED_OUT)
            assertThat(state.getStep("timeoutSurfaced").status()).isEqualTo(StepStatus.COMPLETED)
            assertThat(state.containsStep("timeoutSwallowed")).isFalse()
            // Cancellation surfaced as StepCancellationException: the catch ran, the fall-through did not.
            assertThat(state.getStep("cancelledWait").status()).isEqualTo(StepStatus.CANCELLED)
            assertThat(state.getStep("cancelSurfaced").status()).isEqualTo(StepStatus.COMPLETED)
            assertThat(state.containsStep("cancelSwallowed")).isFalse()
        }
    }

    private class BlockWorkflow {

        fun execute(kontext: Kontext) = with(kontext) {
            try {
                block { waitForEvent("timedOutWait", EventConditions.never(), timeout = 300.milliseconds) }
                awaitExecute("timeoutSwallowed") { _, _ -> mapOf("ran" to true) }
            } catch (e: StepTimedOutException) {
                awaitExecute("timeoutSurfaced") { _, _ -> mapOf("ran" to true) }
            }

            try {
                block { waitForEvent("cancelledWait", EventConditions.never(), timeout = 5.minutes) }
                awaitExecute("cancelSwallowed") { _, _ -> mapOf("ran" to true) }
            } catch (e: StepCancellationException) {
                awaitExecute("cancelSurfaced") { _, _ -> mapOf("ran" to true) }
            }
        }
    }

    @Event(namespace = "io.axoniq.blocksurface", name = "StartBlock")
    data class StartBlockEvent(val id: String)
}

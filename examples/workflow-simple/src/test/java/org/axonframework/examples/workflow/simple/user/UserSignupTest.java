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

import io.axoniq.framework.workflow.configuration.WorkflowModule;
import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContext;
import io.axoniq.framework.workflow.dsl.simple.SimpleWorkflowContextFactory;
import io.axoniq.framework.workflow.runtime.api.execution.context.EventConditions;
import io.axoniq.framework.workflow.runtime.api.execution.context.WorkflowContext;
import io.axoniq.framework.workflow.runtime.api.execution.context.WorkflowStatusChangeListener;
import io.axoniq.framework.workflow.runtime.api.execution.status.WorkflowStatus;
import io.axoniq.framework.workflow.runtime.test.AbstractWorkflowTestBase;
import io.axoniq.framework.workflow.runtime.test.fixture.WorkflowTestDriver;
import io.axoniq.framework.workflow.runtime.test.utils.DelayedPublisher;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static io.axoniq.framework.workflow.runtime.association.Associations.associate;
import static io.axoniq.framework.workflow.dsl.base.BaseWorkflowContext.equalsTo;
import static io.axoniq.framework.workflow.runtime.association.PayloadPropertyValueRetriever.payloadProperty;
import static io.axoniq.framework.workflow.runtime.execution.DefaultEventNameCustomizer.Builder.namespace;
import static io.axoniq.framework.workflow.runtime.execution.PayloadPropertyWorkflowIdProvider.fromPayloadAttribute;
import static io.axoniq.framework.workflow.runtime.test.utils.DelayedPublisher.Schedule.ofMillis;

/**
 * Simple workflow test based on {@link SimpleWorkflowContext}.
 *
 * @author Stefan Dragisic
 * @author Simon Zambrovski
 */
class UserSignupTest {

    void shouldExecuteAllStepsOnFirstRun(
            DelayedPublisher delayedPublisher,
            WorkflowTestDriver testDriver
    ) {

        delayedPublisher.addSchedules(List.of(
                ofMillis(
                        500,
                        new RegistrationReceivedEvent("1", "kermit@muppets.biz", "regular") // don't start
                ),
                ofMillis(
                        500,
                        new RegistrationReceivedEvent("2", "piggy@muppets.biz", "vip") // start
                ),
                ofMillis(2500, new MagicHappenedEvent("Saruman")), // don't correlate
                ofMillis(
                        400,
                        new MagicHappenedEvent("Merlin") // correlate
                )
        ));

        // Arm the publisher to start the delayed execution
        delayedPublisher.start();

        testDriver.executionExists();
        testDriver.noExecution();

        testDriver.historyMatches(h -> h.state().workflowStatus() == WorkflowStatus.COMPLETED);
        testDriver.testingState().hasStepsInOrder(
                "createUser",
                "activateUser",
                "sendWelcomeEmail",
                "waitASecond",
                "waitForMagicToHappen",
                "modifyPayload"
        );
        //noinspection SuspiciousMethodCalls
        testDriver.testingState().payloadMatches(payload -> payload.entrySet().containsAll(Map.of(
                "magician", "Merlin",
                "email", "piggy@muppets.biz",
                "status", "vip",
                "id", "2"
        ).entrySet()));
    }

    /**
     * Test using declarative workflow definition.
     */
    @Nested
    class DeclarativeTest extends AbstractWorkflowTestBase<SimpleWorkflowContext> {

        public DeclarativeTest() {
            super(SimpleWorkflowContext.class, c -> new SimpleWorkflowContextFactory());
        }

        @Override
        protected Function<WorkflowModule.WorkflowDefinitionPhase.DetectionPhase<SimpleWorkflowContext>, WorkflowModule.WorkflowDefinitionPhase.FinalizedPhase<SimpleWorkflowContext>> getDeclaredDefinition() {
            return d -> d
                    .declarative(c -> new UserSignupWorkflow()::execute)
                    .workflowName("MyWorkflow")
                    .on(EventConditions
                                .fromType(
                                        RegistrationReceivedEvent.class,
                                        associate(payloadProperty("status"), equalsTo("vip"))
                                )
                    )
                    .customized((c, w) -> w
                            .eventNameCustomizer(namespace("io.axoniq.dsl.wf.workflow"))
                            .workflowIdProvider(fromPayloadAttribute(c, "id", id -> "signup-" + id))
                            .registerWorkflowStatusChangeListener(WorkflowStatus.COMPLETED,
                                                                  new WorkflowStatusChangeListener() {
                                                                      @Override
                                                                      public <C extends WorkflowContext> void onWorkflowStatus(
                                                                              WorkflowStatus state,
                                                                              C context) {
                                                                          new UserSignupWorkflow().onFinish(state,
                                                                                                            (SimpleWorkflowContext) context);
                                                                      }
                                                                  }
                            )
                    );
        }

        @Test
        void shouldExecuteAllStepsOnFirstRun() {
            UserSignupTest.this.shouldExecuteAllStepsOnFirstRun(delayedPublisher, testDriver);
        }
    }

    /**
     * Test using autodetected workflow definition.
     */
    @Nested
    class AutodetectedTest extends AbstractWorkflowTestBase<SimpleWorkflowContext> {

        public AutodetectedTest() {
            super(SimpleWorkflowContext.class, c -> new SimpleWorkflowContextFactory());
        }

        @Override
        protected Function<WorkflowModule.WorkflowDefinitionPhase.DetectionPhase<SimpleWorkflowContext>, WorkflowModule.WorkflowDefinitionPhase.FinalizedPhase<SimpleWorkflowContext>> getDeclaredDefinition() {
            return d -> d
                    .autodetected(c -> new UserSignupWorkflow());
        }

        @Test
        void shouldExecuteAllStepsOnFirstRun() {
            UserSignupTest.this.shouldExecuteAllStepsOnFirstRun(delayedPublisher, testDriver);
        }
    }
}

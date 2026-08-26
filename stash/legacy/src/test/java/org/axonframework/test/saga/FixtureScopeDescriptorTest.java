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

package org.axonframework.test.saga;

import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;
import org.junit.jupiter.api.*;

/**
 * Test class validating a {@link org.axonframework.messaging.core.ScopeDescriptor}, specifically an {@link
 * org.axonframework.modelling.command.AggregateScopeDescriptor}, can be resolved on Aggregate's message handling
 * functions.
 *
 * @author Steven van Beelen
 */
class FixtureScopeDescriptorTest {

    private FixtureConfiguration fixture;

    @BeforeEach
    void setUp() {
        fixture = new SagaTestFixture<>(TestSaga.class);
    }

    @Test
    @Disabled("TODO revise after Saga support is enabled")
    void resolvesScopeDescriptor() {
        fixture.givenNoPriorActivity()
               .whenPublishingA(new SagaStartEvent("some-identifier"))
//               .expectDispatchedCommandsMatching(payloadsMatching(sequenceOf(matches(
//                       command -> ScopeDescriptorCommand.class.isAssignableFrom(command.getClass()) &&
//                               SagaScopeDescriptor.class.isAssignableFrom(
//                                       ((ScopeDescriptorCommand) command).scopeDescriptor.getClass()
//                               )
//               ))))
        ;
    }

    private static class SagaStartEvent {

        @SuppressWarnings({"FieldCanBeLocal", "unused"})
        private final String identifier;

        private SagaStartEvent(String identifier) {
            this.identifier = identifier;
        }

        public String getIdentifier() {
            return identifier;
        }
    }

    private static class ScopeDescriptorCommand {

        private final ScopeDescriptor scopeDescriptor;

        private ScopeDescriptorCommand(ScopeDescriptor scopeDescriptor) {
            this.scopeDescriptor = scopeDescriptor;
        }
    }

    @SuppressWarnings("unused")
    public static class TestSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "identifier")
        public void on(SagaStartEvent event, ScopeDescriptor scopeDescriptor, CommandGateway commandGateway, ProcessingContext context) {
            commandGateway.send(new ScopeDescriptorCommand(scopeDescriptor), context);
        }
    }
}

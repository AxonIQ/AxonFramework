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

package org.axonframework.test.aggregate;

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.modelling.command.AggregateCreationPolicy;
import org.axonframework.modelling.command.AggregateIdentifier;
import org.axonframework.modelling.command.CreationPolicy;
import org.junit.jupiter.api.*;

import static org.axonframework.modelling.command.AggregateLifecycle.apply;

/**
 * Test class validating a {@link ScopeDescriptor}, specifically an {@link
 * org.axonframework.modelling.command.AggregateScopeDescriptor}, can be resolved on Aggregate's message handling
 * functions.
 *
 * @author Steven van Beelen
 */
class FixtureScopeDescriptorTest {

    private FixtureConfiguration<TestAggregate> fixture;

    @BeforeEach
    void setUp() {
        fixture = new AggregateTestFixture<>(TestAggregate.class);
    }

    @Test
    @Disabled("TODO #3195 - Migration Module")
    void resolvesScopeDescriptor() {
        fixture.givenNoPriorActivity()
               .when("some-identifier")
//               .expectEventsMatching(payloadsMatching(sequenceOf(matches(
//                       event -> ScopeDescriptorEvent.class.isAssignableFrom(event.getClass()) &&
//                               AggregateScopeDescriptor.class.isAssignableFrom(
//                                       ((ScopeDescriptorEvent) event).scopeDescriptor.getClass()
//                               )
//               ))))
        ;
    }

    private static class ScopeDescriptorEvent {

        private final String identifier;
        private final ScopeDescriptor scopeDescriptor;

        private ScopeDescriptorEvent(String identifier, ScopeDescriptor scopeDescriptor) {
            this.identifier = identifier;
            this.scopeDescriptor = scopeDescriptor;
        }
    }

    @SuppressWarnings("unused")
    public static class TestAggregate {

        @SuppressWarnings("FieldCanBeLocal")
        @AggregateIdentifier
        private String identifier;

        @CommandHandler
        @CreationPolicy(AggregateCreationPolicy.ALWAYS)
        public void handle(String identifier, ScopeDescriptor scopeDescriptor) {
            apply(new ScopeDescriptorEvent(identifier, scopeDescriptor));
        }

        @EventSourcingHandler
        public void on(ScopeDescriptorEvent event) {
            identifier = event.identifier;
        }

        public TestAggregate() {
        }
    }
}

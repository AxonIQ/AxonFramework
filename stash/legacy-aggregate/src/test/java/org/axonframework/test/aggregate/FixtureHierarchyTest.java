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
import org.axonframework.modelling.command.AggregateCreationPolicy;
import org.axonframework.modelling.command.AggregateIdentifier;
import org.axonframework.modelling.command.AggregateMember;
import org.axonframework.modelling.command.CreationPolicy;
import org.axonframework.modelling.command.TargetAggregateIdentifier;
import org.junit.jupiter.api.*;

import static org.axonframework.modelling.command.AggregateLifecycle.apply;

/**
 * Test class validating aggregate hierarchy. Note that this is slightly different from aggregate polymorphism, as here
 * we are only dealing with a single concrete type.
 *
 * @author Allard Buijze
 */
class FixtureHierarchyTest {

    private static final String AGGREGATE_IDENTIFIER = "123";

    private FixtureConfiguration<TopAggregate> fixture;

    @BeforeEach
    void setUp() {
        fixture = new AggregateTestFixture<>(TopAggregate.class);
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void creatingAggregateWithHierarchy() {
        fixture.givenNoPriorActivity()
               .when(new CreateAggregateCommand(AGGREGATE_IDENTIFIER))
               .expectEvents(new AggregateCreatedEvent(AGGREGATE_IDENTIFIER));
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void updateAggregateWithHierarchy() {
        fixture.given(new AggregateCreatedEvent(AGGREGATE_IDENTIFIER))
               .when(new UpdateAggregateCommand(AGGREGATE_IDENTIFIER, "some state"))
               .expectEvents(new AggregateUpdatedEvent(AGGREGATE_IDENTIFIER, "some state"));
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void createFirstLevelAggregateMemberWithinHierarchy() {
        fixture.given(new AggregateCreatedEvent(AGGREGATE_IDENTIFIER))
               .when(new CreateFirstLevelAggregateMemberCommand(AGGREGATE_IDENTIFIER))
               .expectEvents(new FirstLevelAggregateMemberCreatedEvent(AGGREGATE_IDENTIFIER));
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void createSecondLevelAggregateMemberWithinHierarchy() {
        fixture.given(new AggregateCreatedEvent(AGGREGATE_IDENTIFIER))
               .when(new CreateSecondLevelAggregateMemberCommand(AGGREGATE_IDENTIFIER))
               .expectEvents(new SecondLevelAggregateMemberCreatedEvent(AGGREGATE_IDENTIFIER));
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void handlingCommandsOnFirstLevelAggregateMemberWithinHierarchy() {
        fixture.given(new AggregateCreatedEvent(AGGREGATE_IDENTIFIER),
                      new FirstLevelAggregateMemberCreatedEvent(AGGREGATE_IDENTIFIER))
               .when(new UpdateFirstLevelAggregateMemberCommand(AGGREGATE_IDENTIFIER))
               .expectEvents(new FirstLevelAggregateMemberUpdatedEvent(AGGREGATE_IDENTIFIER));
    }

    @Test
    @Disabled("TODO #3068 - Revisit Aggregate Modelling")
    void handlingCommandsOnSecondLevelAggregateMemberWithinHierarchy() {
        fixture.given(new AggregateCreatedEvent(AGGREGATE_IDENTIFIER),
                      new SecondLevelAggregateMemberCreatedEvent(AGGREGATE_IDENTIFIER))
               .when(new UpdateSecondLevelAggregateMemberCommand(AGGREGATE_IDENTIFIER))
               .expectEvents(new SecondLevelAggregateMemberUpdatedEvent(AGGREGATE_IDENTIFIER));
    }

    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private static abstract class RootAggregate {

        @AggregateIdentifier
        private String id;
        private String someState;

        @CommandHandler
        public abstract void handle(UpdateAggregateCommand command);

        @EventSourcingHandler
        protected void on(AggregateCreatedEvent event) {
            id = event.getAggregateIdentifier();
        }

        @EventSourcingHandler
        public void on(AggregateUpdatedEvent event) {
            someState = event.getSomeState();
        }
    }

    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private static abstract class FirstLevelAggregate extends RootAggregate {

        @AggregateMember
        private FirstLevelAggregateMember aggregateMember;

        @CommandHandler
        public void handle(CreateFirstLevelAggregateMemberCommand command) {
            apply(new FirstLevelAggregateMemberCreatedEvent(command.getAggregateIdentifier()));
        }

        @EventSourcingHandler
        public void on(FirstLevelAggregateMemberCreatedEvent event) {
            aggregateMember = new FirstLevelAggregateMember();
        }
    }

    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private static abstract class SecondLevelAggregate extends FirstLevelAggregate {

        @AggregateMember
        private SecondLevelAggregateMember aggregateMember;

        @CommandHandler
        public void handle(CreateSecondLevelAggregateMemberCommand command) {
            apply(new SecondLevelAggregateMemberCreatedEvent(command.getAggregateIdentifier()));
        }

        @EventSourcingHandler
        public void on(SecondLevelAggregateMemberCreatedEvent event) {
            aggregateMember = new SecondLevelAggregateMember();
        }
    }

    private static class TopAggregate extends SecondLevelAggregate {

        @CommandHandler
        @CreationPolicy(AggregateCreationPolicy.ALWAYS)
        public void handle(CreateAggregateCommand command) {
            apply(new AggregateCreatedEvent(command.getAggregateIdentifier()));
        }

        @Override
        public void handle(UpdateAggregateCommand command) {
            apply(new AggregateUpdatedEvent(command.getAggregateIdentifier(), command.getSomeState()));
        }

        @SuppressWarnings("unused")
        public TopAggregate() {
            // Required by Axon
        }
    }

    private static class FirstLevelAggregateMember {

        @CommandHandler
        public void handle(UpdateFirstLevelAggregateMemberCommand command) {
            apply(new FirstLevelAggregateMemberUpdatedEvent(command.aggregateIdentifier));
        }
    }

    private static class SecondLevelAggregateMember {

        @CommandHandler
        public void handle(UpdateSecondLevelAggregateMemberCommand command) {
            apply(new SecondLevelAggregateMemberUpdatedEvent(command.aggregateIdentifier));
        }
    }

    private static class CreateAggregateCommand {

        @TargetAggregateIdentifier
        private final String aggregateIdentifier;

        CreateAggregateCommand(String aggregateIdentifier) {
            this.aggregateIdentifier = aggregateIdentifier;
        }

        public String getAggregateIdentifier() {
            return aggregateIdentifier;
        }
    }

    private static class AggregateCreatedEvent {

        private final String aggregateIdentifier;

        AggregateCreatedEvent(String aggregateIdentifier) {
            this.aggregateIdentifier = aggregateIdentifier;
        }

        public String getAggregateIdentifier() {
            return aggregateIdentifier;
        }
    }

    private static class UpdateAggregateCommand {

        @TargetAggregateIdentifier
        private final String aggregateIdentifier;
        private final String someState;

        UpdateAggregateCommand(String aggregateIdentifier, String someState) {
            this.aggregateIdentifier = aggregateIdentifier;
            this.someState = someState;
        }

        public String getAggregateIdentifier() {
            return aggregateIdentifier;
        }

        public String getSomeState() {
            return someState;
        }
    }

    private static class AggregateUpdatedEvent {

        private final String aggregateIdentifier;
        private final String someState;

        AggregateUpdatedEvent(String aggregateIdentifier, String someState) {
            this.aggregateIdentifier = aggregateIdentifier;
            this.someState = someState;
        }

        @SuppressWarnings("unused")
        public String getAggregateIdentifier() {
            return aggregateIdentifier;
        }

        public String getSomeState() {
            return someState;
        }
    }

    private static class CreateFirstLevelAggregateMemberCommand {

        @TargetAggregateIdentifier
        private final String aggregateIdentifier;

        CreateFirstLevelAggregateMemberCommand(String aggregateIdentifier) {
            this.aggregateIdentifier = aggregateIdentifier;
        }

        public String getAggregateIdentifier() {
            return aggregateIdentifier;
        }
    }

    private static class FirstLevelAggregateMemberCreatedEvent {

        private final String aggregateIdentifier;

        FirstLevelAggregateMemberCreatedEvent(String aggregateIdentifier) {
            this.aggregateIdentifier = aggregateIdentifier;
        }

        @SuppressWarnings("unused")
        public String getAggregateIdentifier() {
            return aggregateIdentifier;
        }
    }

    private static class UpdateFirstLevelAggregateMemberCommand {

        @TargetAggregateIdentifier
        private final String aggregateIdentifier;

        UpdateFirstLevelAggregateMemberCommand(String aggregateIdentifier) {
            this.aggregateIdentifier = aggregateIdentifier;
        }

        @SuppressWarnings("unused")
        public String getAggregateIdentifier() {
            return aggregateIdentifier;
        }
    }

    private static class FirstLevelAggregateMemberUpdatedEvent {

        private final String aggregateIdentifier;

        FirstLevelAggregateMemberUpdatedEvent(String aggregateIdentifier) {
            this.aggregateIdentifier = aggregateIdentifier;
        }

        @SuppressWarnings("unused")
        public String getAggregateIdentifier() {
            return aggregateIdentifier;
        }
    }

    private static class CreateSecondLevelAggregateMemberCommand {

        @TargetAggregateIdentifier
        private final String aggregateIdentifier;

        CreateSecondLevelAggregateMemberCommand(String aggregateIdentifier) {
            this.aggregateIdentifier = aggregateIdentifier;
        }

        public String getAggregateIdentifier() {
            return aggregateIdentifier;
        }
    }

    private static class SecondLevelAggregateMemberCreatedEvent {

        private final String aggregateIdentifier;

        SecondLevelAggregateMemberCreatedEvent(String aggregateIdentifier) {
            this.aggregateIdentifier = aggregateIdentifier;
        }

        @SuppressWarnings("unused")
        public String getAggregateIdentifier() {
            return aggregateIdentifier;
        }
    }

    private static class UpdateSecondLevelAggregateMemberCommand {

        @TargetAggregateIdentifier
        private final String aggregateIdentifier;

        UpdateSecondLevelAggregateMemberCommand(String aggregateIdentifier) {
            this.aggregateIdentifier = aggregateIdentifier;
        }

        @SuppressWarnings("unused")
        public String getAggregateIdentifier() {
            return aggregateIdentifier;
        }
    }

    private static class SecondLevelAggregateMemberUpdatedEvent {

        private final String aggregateIdentifier;

        SecondLevelAggregateMemberUpdatedEvent(String aggregateIdentifier) {
            this.aggregateIdentifier = aggregateIdentifier;
        }

        @SuppressWarnings("unused")
        public String getAggregateIdentifier() {
            return aggregateIdentifier;
        }
    }
}

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
import org.axonframework.messaging.eventsourcing.AggregateDeletedException;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.modelling.command.AggregateCreationPolicy;
import org.axonframework.modelling.command.AggregateIdentifier;
import org.axonframework.modelling.command.CommandHandlerInterceptor;
import org.axonframework.modelling.command.CreationPolicy;
import org.axonframework.modelling.command.TargetAggregateIdentifier;
import org.junit.jupiter.api.*;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.axonframework.modelling.command.AggregateLifecycle.apply;
import static org.axonframework.modelling.command.AggregateLifecycle.markDeleted;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixture tests for validating {@link CreationPolicy} annotated command handlers.
 *
 * @author Marc Gathier
 * @author Steven van Beelen
 */
class FixtureCreationPolicyTest {

    private static final ComplexAggregateId AGGREGATE_ID = new ComplexAggregateId(UUID.randomUUID(), 42);
    private static final boolean PUBLISH_EVENTS = true;
    private static final boolean PUBLISH_NO_EVENTS = false;

    private FixtureConfiguration<TestAggregate> fixture;

    private static AtomicBoolean intercepted;

    @BeforeEach
    void setUp() {
        fixture = new AggregateTestFixture<>(TestAggregate.class);

        intercepted = new AtomicBoolean(false);
    }

    @Test
    @Disabled("TODO #3384 - Revisit Creation Policy behavior")
    void createOrUpdatePolicyForNewInstance() {
        fixture.givenNoPriorActivity()
               .when(new CreateOrUpdateCommand(AGGREGATE_ID, PUBLISH_EVENTS))
               .expectEvents(new CreatedOrUpdatedEvent(AGGREGATE_ID))
               .expectSuccessfulHandlerExecution();
        assertTrue(intercepted.get());
    }

    @Test
    @Disabled("TODO #3384 - Revisit Creation Policy behavior")
    void createOrUpdatePolicyForExistingInstance() {
        fixture.given(new CreatedOrUpdatedEvent(AGGREGATE_ID))
               .when(new CreateOrUpdateCommand(AGGREGATE_ID, PUBLISH_EVENTS))
               .expectEvents(new CreatedOrUpdatedEvent(AGGREGATE_ID))
               .expectSuccessfulHandlerExecution();
        assertTrue(intercepted.get());
    }

    @Test
    @Disabled("TODO #3384 - Revisit Creation Policy behavior")
    void alwaysCreatePolicyWithoutResultReturnsAggregateId() {
        fixture.givenNoPriorActivity()
               .when(new AlwaysCreateWithoutResultCommand(AGGREGATE_ID, PUBLISH_EVENTS))
               .expectEvents(new AlwaysCreatedEvent(AGGREGATE_ID))
               .expectResultMessagePayload(AGGREGATE_ID)
               .expectSuccessfulHandlerExecution();
        assertTrue(intercepted.get());
    }

    @Test
    @Disabled("TODO #3384 - Revisit Creation Policy behavior")
    void alwaysCreatePolicyWithResultReturnsCommandHandlingResult() {
        Object testResult = "some-result";
        fixture.givenNoPriorActivity()
               .when(new AlwaysCreateWithResultCommand(AGGREGATE_ID, testResult))
               .expectEvents(new AlwaysCreatedEvent(AGGREGATE_ID))
               .expectResultMessagePayload(testResult)
               .expectSuccessfulHandlerExecution();
        assertTrue(intercepted.get());
    }

    @Test
    @Disabled("TODO #3384 - Revisit Creation Policy behavior")
    void alwaysCreatePolicyWithResultReturnsNullCommandHandlingResult() {
        fixture.givenNoPriorActivity()
               .when(new AlwaysCreateWithResultCommand(AGGREGATE_ID, null))
               .expectEvents(new AlwaysCreatedEvent(AGGREGATE_ID))
               .expectResultMessagePayload(null)
               .expectSuccessfulHandlerExecution();
        assertTrue(intercepted.get());
    }

    @Test
    @Disabled("TODO #3384 - Revisit Creation Policy behavior")
    void neverCreatePolicy() {
        fixture.given(new CreatedOrUpdatedEvent(AGGREGATE_ID))
               .when(new ExecuteOnExistingCommand(AGGREGATE_ID))
               .expectEvents(new ExecutedOnExistingEvent(AGGREGATE_ID))
               .expectSuccessfulHandlerExecution();
        assertTrue(intercepted.get());
    }

    @Test
    @Disabled("TODO #3384 - Revisit Creation Policy behavior")
    void alwaysCreatePolicyWithStateReturnsStateInCommandHandlingResult() {
        fixture.givenNoPriorActivity()
               .when(new AlwaysCreateWithEventSourcedResultCommand(AGGREGATE_ID))
               .expectEvents(new AlwaysCreatedEvent(AGGREGATE_ID))
               .expectResultMessagePayload(AGGREGATE_ID)
               .expectSuccessfulHandlerExecution();
        assertTrue(intercepted.get());
    }

    @Test
    @Disabled("TODO #3073 - Revisit Aggregate Test Fixture")
    void alwaysCreateExceptionsArePropagates() {
        fixture.givenNoPriorActivity()
               .when(new AlwaysCreateWithEventSourcedResultCommand(AGGREGATE_ID, PUBLISH_NO_EVENTS))
               .expectNoEvents()
               .expectException(RuntimeException.class);
        assertTrue(intercepted.get());
    }

    @Test
    @Disabled("TODO #3384 - Revisit Creation Policy behavior")
    void createOrUpdatePolicyDoesNotPublishAnyEvents() {
        fixture.givenNoPriorActivity()
               .when(new CreateOrUpdateCommand(AGGREGATE_ID, PUBLISH_NO_EVENTS))
               .expectNoEvents()
               .expectSuccessfulHandlerExecution();
    }

    @Test
    @Disabled("TODO #3384 - Revisit Creation Policy behavior")
    void whenPrivateConstructorCombinedWithCreateIfMissingPolicyThenAggregateWorksAsExpected() {
        new AggregateTestFixture<>(TestAggregateWithPrivateNoArgConstructor.class)
                .givenNoPriorActivity()
                .when(new CreateOrUpdateCommand(AGGREGATE_ID, PUBLISH_EVENTS))
                .expectEvents(new CreatedOrUpdatedEvent(AGGREGATE_ID))
                .expectSuccessfulHandlerExecution();
    }

    @Test
    @Disabled("TODO #3384 - Revisit Creation Policy behavior")
    void whenPrivateConstructorCombinedWithAlwaysPolicyThenAggregateWorksAsExpected() {
        new AggregateTestFixture<>(TestAggregateWithPrivateNoArgConstructor.class)
                .givenNoPriorActivity()
                .when(new AlwaysCreateWithoutResultCommand(AGGREGATE_ID, PUBLISH_EVENTS))
                .expectEvents(new AlwaysCreatedEvent(AGGREGATE_ID))
                .expectSuccessfulHandlerExecution();
    }

    @Test
    @Disabled("TODO #3384 - Revisit Creation Policy behavior")
    void whenProtectedConstructorCombinedWithCreateIfMissingPolicyThenAggregateWorksAsExpected() {
        new AggregateTestFixture<>(TestAggregateWithProtectedNoArgConstructor.class)
                .givenNoPriorActivity()
                .when(new CreateOrUpdateCommand(AGGREGATE_ID, PUBLISH_EVENTS))
                .expectEvents(new CreatedOrUpdatedEvent(AGGREGATE_ID))
                .expectSuccessfulHandlerExecution();
    }

    @Test
    @Disabled("TODO #3384 - Revisit Creation Policy behavior")
    void whenProtectedConstructorCombinedWithAlwaysPolicyThenAggregateWorksAsExpected() {
        new AggregateTestFixture<>(TestAggregateWithProtectedNoArgConstructor.class)
                .givenNoPriorActivity()
                .when(new AlwaysCreateWithoutResultCommand(AGGREGATE_ID, PUBLISH_EVENTS))
                .expectEvents(new AlwaysCreatedEvent(AGGREGATE_ID))
                .expectSuccessfulHandlerExecution();
    }

    @Test
    @Disabled("TODO #3384 - Revisit Creation Policy behavior")
    void whenPolymorphicAggregateWithUniquelyNamedCreateIfMissingPolicyOnChildThenWorksAsExpected() {
        new AggregateTestFixture<>(TestAggregateParentForPolymorphicCase.class)
                .withSubtypes(TestAggregateChildForPolymorphicCase.class)
                .givenNoPriorActivity()
                .when(new CreateOrUpdateCommand(AGGREGATE_ID, PUBLISH_EVENTS))
                .expectEvents(new CreatedOrUpdatedEvent(AGGREGATE_ID))
                .expectSuccessfulHandlerExecution();
    }

    @Test
    @Disabled("TODO #3384 - Revisit Creation Policy behavior")
    void whenPolymorphicAggregateWithUniquelyNamedAlwaysPolicyOnChildThenWorksAsExpected() {
        new AggregateTestFixture<>(TestAggregateParentForPolymorphicCase.class)
                .withSubtypes(TestAggregateChildForPolymorphicCase.class)
                .givenNoPriorActivity()
                .when(new AlwaysCreateWithoutResultCommand(AGGREGATE_ID, PUBLISH_EVENTS))
                .expectEvents(new AlwaysCreatedEvent(AGGREGATE_ID))
                .expectSuccessfulHandlerExecution();
    }

    @Test
    @Disabled("TODO #3384 - Revisit Creation Policy behavior")
    void markedDeletedAggregateDoesNotAllowForCreateIfMissingButRethrowsAggregateDeletedException() {
        fixture.given(new CreatedEvent(AGGREGATE_ID), new MarkedDeleted(AGGREGATE_ID))
               .when(new CreateOrUpdateCommand(AGGREGATE_ID, PUBLISH_EVENTS))
               .expectNoEvents()
               .expectException(AggregateDeletedException.class);
    }

    private record CreateOrUpdateCommand(
            @TargetAggregateIdentifier ComplexAggregateId id,
            boolean shouldPublishEvent
    ) {

    }

    private record AlwaysCreateWithoutResultCommand(
            @TargetAggregateIdentifier ComplexAggregateId id,
            boolean shouldPublishEvent
    ) {

    }

    private record AlwaysCreateWithResultCommand(
            @TargetAggregateIdentifier ComplexAggregateId id,
            Object result
    ) {

    }

    private static class AlwaysCreateWithEventSourcedResultCommand {

        @TargetAggregateIdentifier
        private final ComplexAggregateId id;
        private final boolean success;

        private AlwaysCreateWithEventSourcedResultCommand(ComplexAggregateId id) {
            this(id, true);
        }

        private AlwaysCreateWithEventSourcedResultCommand(ComplexAggregateId id, boolean success) {
            this.id = id;
            this.success = success;
        }

        public ComplexAggregateId getId() {
            return id;
        }
    }

    private record ExecuteOnExistingCommand(@TargetAggregateIdentifier ComplexAggregateId id) {

        @Override
        public ComplexAggregateId id() {
            return id;
        }
    }

    private record CreatedEvent(ComplexAggregateId id) {

    }

    private record CreatedOrUpdatedEvent(ComplexAggregateId id) {

    }

    private record AlwaysCreatedEvent(ComplexAggregateId id) {

    }

    private record ExecutedOnExistingEvent(ComplexAggregateId id) {

    }

    private record MarkedDeleted(ComplexAggregateId id) {

    }

    @SuppressWarnings("unused")
    private static class TestAggregate implements TestAggregateInterface {

        @AggregateIdentifier
        private ComplexAggregateId id;

        public TestAggregate() {
        }

        @CommandHandlerInterceptor
        public void intercept(Object command) {
            intercepted.set(true);
        }

        @CommandHandler
        @CreationPolicy(AggregateCreationPolicy.CREATE_IF_MISSING)
        public void handle(CreateOrUpdateCommand command) {
            if (command.shouldPublishEvent()) {
                apply(new CreatedOrUpdatedEvent(command.id()));
            }
        }

        @Override
        public void handle(AlwaysCreateWithoutResultCommand command) {
            if (command.shouldPublishEvent()) {
                apply(new AlwaysCreatedEvent(command.id()));
            }
        }

        @CommandHandler
        @CreationPolicy(AggregateCreationPolicy.ALWAYS)
        public Object handle(AlwaysCreateWithResultCommand command) {
            apply(new AlwaysCreatedEvent(command.id()));
            return command.result();
        }

        @CommandHandler
        @CreationPolicy(AggregateCreationPolicy.ALWAYS)
        public ComplexAggregateId handle(AlwaysCreateWithEventSourcedResultCommand command) {
            apply(new AlwaysCreatedEvent(command.getId()));
            if (!command.success) {
                throw new RuntimeException("Simulating failure in a creation handler");
            }
            // On apply, the event sourcing handlers should be invoked first.
            // Hence, we should be able to return the identifier of the aggregate directly.
            return id;
        }

        @CommandHandler
        @CreationPolicy(AggregateCreationPolicy.NEVER)
        public void handle(ExecuteOnExistingCommand command) {
            apply(new ExecutedOnExistingEvent(command.id()));
        }

        @EventSourcingHandler
        public void on(CreatedOrUpdatedEvent event) {
            this.id = event.id();
        }

        @EventSourcingHandler
        public void on(AlwaysCreatedEvent event) {
            this.id = event.id();
        }

        @EventSourcingHandler
        public void on(MarkedDeleted event) {
            markDeleted();
        }
    }

    private interface TestAggregateInterface {

        @CommandHandler
        @CreationPolicy(AggregateCreationPolicy.CREATE_IF_MISSING)
        void handle(CreateOrUpdateCommand command);

        @CommandHandler
        @CreationPolicy(AggregateCreationPolicy.ALWAYS)
        void handle(AlwaysCreateWithoutResultCommand command);
    }

    private static class TestAggregateWithPrivateNoArgConstructor {

        @AggregateIdentifier
        private ComplexAggregateId id;

        private TestAggregateWithPrivateNoArgConstructor() {
            // Constructor made private on purpose for testing.
        }

        @CommandHandler
        @CreationPolicy(AggregateCreationPolicy.CREATE_IF_MISSING)
        public void handle(CreateOrUpdateCommand command) {
            if (command.shouldPublishEvent()) {
                apply(new CreatedOrUpdatedEvent(command.id()));
            }
        }

        @CommandHandler
        @CreationPolicy(AggregateCreationPolicy.ALWAYS)
        public void handle(AlwaysCreateWithoutResultCommand command) {
            if (command.shouldPublishEvent()) {
                apply(new AlwaysCreatedEvent(command.id()));
            }
        }

        @EventSourcingHandler
        private void on(CreatedOrUpdatedEvent event) {
            this.id = event.id();
        }

        @EventSourcingHandler
        public void on(AlwaysCreatedEvent event) {
            this.id = event.id();
        }
    }

    private static class TestAggregateWithProtectedNoArgConstructor {

        @AggregateIdentifier
        private ComplexAggregateId id;

        protected TestAggregateWithProtectedNoArgConstructor() {
            // Constructor made protected on purpose for testing.
        }

        @CommandHandler
        @CreationPolicy(AggregateCreationPolicy.CREATE_IF_MISSING)
        public void handle(CreateOrUpdateCommand command) {
            if (command.shouldPublishEvent()) {
                apply(new CreatedOrUpdatedEvent(command.id()));
            }
        }

        @CommandHandler
        @CreationPolicy(AggregateCreationPolicy.ALWAYS)
        public void handle(AlwaysCreateWithoutResultCommand command) {
            if (command.shouldPublishEvent()) {
                apply(new AlwaysCreatedEvent(command.id()));
            }
        }

        @EventSourcingHandler
        private void on(CreatedOrUpdatedEvent event) {
            this.id = event.id();
        }

        @EventSourcingHandler
        public void on(AlwaysCreatedEvent event) {
            this.id = event.id();
        }
    }

    private static abstract class TestAggregateParentForPolymorphicCase {

        @AggregateIdentifier
        protected ComplexAggregateId id;

        public TestAggregateParentForPolymorphicCase() {
            // Constructor made public on purpose for testing.
        }
    }

    private static class TestAggregateChildForPolymorphicCase extends TestAggregateParentForPolymorphicCase {

        public TestAggregateChildForPolymorphicCase() {
            // Constructor made public on purpose for testing.
        }

        @CommandHandler
        @CreationPolicy(AggregateCreationPolicy.CREATE_IF_MISSING)
        public void handle(CreateOrUpdateCommand command) {
            if (command.shouldPublishEvent()) {
                apply(new CreatedOrUpdatedEvent(command.id()));
            }
        }

        @CommandHandler
        @CreationPolicy(AggregateCreationPolicy.ALWAYS)
        public void handle(AlwaysCreateWithoutResultCommand command) {
            if (command.shouldPublishEvent()) {
                apply(new AlwaysCreatedEvent(command.id()));
            }
        }

        @EventSourcingHandler
        private void on(CreatedOrUpdatedEvent event) {
            this.id = event.id();
        }

        @EventSourcingHandler
        public void on(AlwaysCreatedEvent event) {
            this.id = event.id();
        }
    }

    /**
     * Test identifier introduced for issue <a
     * href="https://github.com/AxonFramework/AxonFramework/pull/1356">#1356</a>.
     */
    private record ComplexAggregateId(
            UUID actualId,
            Integer someOtherField
    ) {

        @Override
        public String toString() {
            return actualId.toString();
        }
    }
}

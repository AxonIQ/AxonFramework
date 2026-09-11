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

import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.CoreMatchers.any;

/**
 * How the fixture answers the parts of the Axon Framework 4 API that depend on deadlines and the event scheduler.
 * <p>
 * Every operation fails fast and names itself because {@code axon-legacy} carries neither a scheduler nor a deadline
 * manager. This keeps an Axon Framework 4 suite compiling without allowing an assertion to pass without exercising
 * the behaviour it claims to verify.
 *
 * @author Mateusz Nowak
 */
class SagaTestFixtureUnsupportedOperationsTest {

    private final SagaTestFixture<OrderSaga> fixture = new SagaTestFixture<>(OrderSaga.class);

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    private record Call(String name, Runnable invocation) {

    }

    private static Stream<DynamicTest> reportsItself(List<Call> calls) {
        return calls.stream().map(call -> DynamicTest.dynamicTest(
                call.name(),
                () -> assertThatThrownBy(call.invocation()::run)
                        .isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining("[" + call.name() + "]")
                        .hasMessageContaining("axon-legacy")
        ));
    }

    @TestFactory
    Stream<DynamicTest> everyTimeRelatedFixtureMethodReportsItself() {
        return reportsItself(List.of(
                new Call("givenCurrentTime", () -> fixture.givenCurrentTime(Instant.now())),
                new Call("andThenTimeElapses", () -> fixture.andThenTimeElapses(Duration.ofMinutes(1))),
                new Call("andThenTimeAdvancesTo", () -> fixture.andThenTimeAdvancesTo(Instant.now())),
                new Call("whenTimeElapses", () -> fixture.whenTimeElapses(Duration.ofMinutes(1))),
                new Call("whenTimeAdvancesTo", () -> fixture.whenTimeAdvancesTo(Instant.now())),
                new Call("currentTime", fixture::currentTime)
        ));
    }

    @Nested
    class ScheduledEventAssertions {

        @TestFactory
        Stream<DynamicTest> assertingSomethingIsScheduledReportsItself() {
            FixtureExecutionResult result = whenSomethingHappened();
            Instant at = Instant.EPOCH;
            Duration in = Duration.ofMinutes(10);
            return reportsItself(List.of(
                    new Call("expectScheduledEventMatching", () -> result.expectScheduledEventMatching(in, anyEvent())),
                    new Call("expectScheduledEvent", () -> result.expectScheduledEvent(in, new OrderShipped("s"))),
                    new Call("expectScheduledEventOfType",
                             () -> result.expectScheduledEventOfType(in, OrderShipped.class)),
                    new Call("expectScheduledEventMatching", () -> result.expectScheduledEventMatching(at, anyEvent())),
                    new Call("expectScheduledEvent", () -> result.expectScheduledEvent(at, new OrderShipped("s"))),
                    new Call("expectScheduledEventOfType",
                             () -> result.expectScheduledEventOfType(at, OrderShipped.class))
            ));
        }

        @TestFactory
        Stream<DynamicTest> assertingNothingIsScheduledReportsItself() {
            FixtureExecutionResult result = whenSomethingHappened();
            Instant at = Instant.EPOCH;
            Duration in = Duration.ofMinutes(10);
            return reportsItself(List.of(
                    new Call("expectNoScheduledEvents", result::expectNoScheduledEvents),
                    new Call("expectNoScheduledEventMatching",
                             () -> result.expectNoScheduledEventMatching(in, anyEvent())),
                    new Call("expectNoScheduledEvent", () -> result.expectNoScheduledEvent(in, new OrderShipped("s"))),
                    new Call("expectNoScheduledEventOfType",
                             () -> result.expectNoScheduledEventOfType(in, OrderShipped.class)),
                    new Call("expectNoScheduledEventMatching",
                             () -> result.expectNoScheduledEventMatching(at, anyEvent())),
                    new Call("expectNoScheduledEvent", () -> result.expectNoScheduledEvent(at, new OrderShipped("s"))),
                    new Call("expectNoScheduledEventOfType",
                             () -> result.expectNoScheduledEventOfType(at, OrderShipped.class))
            ));
        }
    }

    @Nested
    class ScheduledDeadlineAssertions {

        @TestFactory
        Stream<DynamicTest> assertingSomethingIsScheduledOrTriggeredReportsItself() {
            FixtureExecutionResult result = whenSomethingHappened();
            Instant at = Instant.EPOCH;
            Duration in = Duration.ofMinutes(10);
            return reportsItself(List.of(
                    new Call("expectScheduledDeadline", () -> result.expectScheduledDeadline(in, "deadline")),
                    new Call("expectScheduledDeadlineOfType",
                             () -> result.expectScheduledDeadlineOfType(in, String.class)),
                    new Call("expectScheduledDeadlineWithName",
                             () -> result.expectScheduledDeadlineWithName(in, "name")),
                    new Call("expectScheduledDeadline", () -> result.expectScheduledDeadline(at, "deadline")),
                    new Call("expectScheduledDeadlineOfType",
                             () -> result.expectScheduledDeadlineOfType(at, String.class)),
                    new Call("expectScheduledDeadlineWithName",
                             () -> result.expectScheduledDeadlineWithName(at, "name")),
                    new Call("expectTriggeredDeadlines", () -> result.expectTriggeredDeadlines("deadline")),
                    new Call("expectTriggeredDeadlinesWithName",
                             () -> result.expectTriggeredDeadlinesWithName("name")),
                    new Call("expectTriggeredDeadlinesOfType",
                             () -> result.expectTriggeredDeadlinesOfType(String.class))
            ));
        }

        @TestFactory
        Stream<DynamicTest> assertingNothingIsScheduledOrTriggeredReportsItself() {
            FixtureExecutionResult result = whenSomethingHappened();
            Instant at = Instant.EPOCH;
            Instant until = Instant.EPOCH.plusSeconds(60);
            Duration in = Duration.ofMinutes(10);
            return reportsItself(List.of(
                    new Call("expectNoScheduledDeadlines", result::expectNoScheduledDeadlines),
                    new Call("expectNoScheduledDeadline", () -> result.expectNoScheduledDeadline(in, "deadline")),
                    new Call("expectNoScheduledDeadlineOfType",
                             () -> result.expectNoScheduledDeadlineOfType(in, String.class)),
                    new Call("expectNoScheduledDeadlineWithName",
                             () -> result.expectNoScheduledDeadlineWithName(in, "name")),
                    new Call("expectNoScheduledDeadline", () -> result.expectNoScheduledDeadline(at, "deadline")),
                    new Call("expectNoScheduledDeadlineOfType",
                             () -> result.expectNoScheduledDeadlineOfType(at, String.class)),
                    new Call("expectNoScheduledDeadlineWithName",
                             () -> result.expectNoScheduledDeadlineWithName(at, "name")),
                    new Call("expectNoScheduledDeadline",
                             () -> result.expectNoScheduledDeadline(at, until, "deadline")),
                    new Call("expectNoScheduledDeadlineOfType",
                             () -> result.expectNoScheduledDeadlineOfType(at, until, String.class)),
                    new Call("expectNoScheduledDeadlineWithName",
                             () -> result.expectNoScheduledDeadlineWithName(at, until, "name")),
                    // The Axon Framework 4 idiom for "nothing was triggered" is the no-argument call.
                    new Call("expectTriggeredDeadlines", result::expectTriggeredDeadlines),
                    new Call("expectTriggeredDeadlinesWithName", result::expectTriggeredDeadlinesWithName),
                    new Call("expectTriggeredDeadlinesOfType", result::expectTriggeredDeadlinesOfType)
            ));
        }
    }

    @Nested
    class EverythingElseStillWorks {

        @Test
        void theAssertionsThatDoNotNeedDeadlinesAreUnaffected() {
            fixture.givenAPublished(new OrderPlaced("order-1"))
                   .whenPublishingA(new OrderShipped("shipment-of-order-1"), Map.of())
                   .expectActiveSagas(1)
                   .expectSuccessfulHandlerExecution()
                   .expectNoDispatchedCommands();
        }
    }

    private static Matcher<? super EventMessage> anyEvent() {
        return any(EventMessage.class);
    }

    private FixtureExecutionResult whenSomethingHappened() {
        return fixture.givenAPublished(new OrderPlaced("order-1"))
                      .whenPublishingA(new OrderShipped("shipment-of-order-1"));
    }

    public record OrderPlaced(String orderId) {

    }

    public record OrderShipped(String shipmentId) {

    }

    @SuppressWarnings({"unused", "removal"})
    public static class OrderSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "orderId")
        public void on(OrderPlaced event) {
            // Starting the saga is enough here.
        }

        @SagaEventHandler(associationProperty = "shipmentId")
        public void on(OrderShipped event) {
            // Nothing to do; the assertions under test never reach a saga.
        }
    }
}

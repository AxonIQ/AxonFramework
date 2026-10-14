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

package org.axonframework.test.fixture;

import org.axonframework.common.FutureUtils;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.unitofwork.UnitOfWork;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Interface describing the operations available on a test phase for testing Axon-based applications using a
 * given-when-then pattern. The test phase provides a fluent API to configure and execute tests against Axon
 * components.
 *
 * <p>
 * This interface defines four primary phases of a test:
 * <ul>
 *   <li>{@link Setup} - Initial configuration of the test fixture</li>
 *   <li>{@link Given} - Defining the initial state of the system before testing by events and commands</li>
 *   <li>{@link When} - Executing commands to test</li>
 *   <li>{@link Then} - Validating the results of the test</li>
 * </ul>
 * <p>
 * The test fixture manages {@link UnitOfWork} instances during test execution,
 * automatically committing as appropriate. During the Given phase, each operation (like {@link Given#event}},
 * {@link Given#command} or even batched like {@link Given#events} and {@link Given#commands})
 * is executed in its own separate {@link UnitOfWork} that is committed immediately after execution. In the When phase, a single Unit of Work is started
 * and committed after the command is executed. The Then phase only validates the results.
 * <p>
 * The test phases operates on components defined in {@link Configuration} that you pass to the fixture during its construction.
 * <p>
 * Typical usage example:<br/>
 * <pre>
 * {@code
 * var fixture = AxonTestFixture.with(configurer);
 *
 * fixture.given()
 *        .event(new AccountCreatedEvent("account-1"))
 *        .when()
 *        .command(new WithdrawMoneyCommand("account-1", 100.00))
 *        .then()
 *        .success()
 *        .events(new MoneyWithdrawnEvent("account-1", 100.00));
 * }
 * </pre>
 * <p>
 * Example with chaining multiple test scenarios using {@code and()}:<br/>
 * <pre>
 * {@code
 * var fixture = AxonTestFixture.with(configurer);
 *
 * fixture.given()
 *        .event(new AccountCreatedEvent("account-1", 500.00))
 *        .when()
 *        .command(new WithdrawMoneyCommand("account-1", 100.00))
 *        .then()
 *        .events(new MoneyWithdrawnEvent("account-1", 100.00))
 *        .success()
 *        .and()
 *        .when()
 *        .command(new WithdrawMoneyCommand("account-1", 500.00))
 *        .then()
 *        .exception(InsufficientBalanceException.class)
 *        .noEvents();
 * }
 * </pre>
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @since 5.0.0
 */
public interface AxonTestPhase {

    /**
     * Interface describing the initial setup phase of a test fixture. This phase allows configuring the test fixture
     * before starting the test execution.
     * <p>
     * From this phase, you can transition to either the Given phase to define initial state, or directly to the When
     * phase if no prior state is needed.
     */
    interface Setup {

        /**
         * Transition to the Given phase to define the initial state of the system before testing.
         *
         * @return A {@link Given} instance that allows defining the initial state.
         */
        AxonTestPhase.Given given();

        /**
         * Transition directly to the When phase, skipping the Given phase, which implies no prior state.
         *
         * @return A {@link When} instance that allows executing the test.
         */
        When when();

        /**
         * Stops the fixture, releasing any active resources, like registered handlers or pending event processing
         * tasks.
         */
        void stop();
    }

    /**
     * Interface describing the operations available in the Given phase of the test fixture execution. This phase is
     * used to define the initial state of the system before executing the test action.
     */
    interface Given {

        /**
         * Indicates that no relevant activities like commands or events have occurred in the past. This also means that
         * no previous state is present in the system.
         *
         * @return The current Given instance, for fluent interfacing
         */
        Given noPriorActivity();

        /**
         * Configures a single event as part of the "given" state. This event will be published with empty metadata.
         * <p>
         * The {@code payload} parameter accepts either an event payload object or an {@link EventMessage}.
         * If an {@link EventMessage} is provided, it will be used directly.
         *
         * @param payload The event payload or {@link EventMessage} to publish.
         * @return The current Given instance, for fluent interfacing.
         */
        default Given event(Object payload) {
            return event(payload, Metadata.emptyInstance());
        }

        /**
         * Configures a single event with the given {@code metadata} as part of the "given" state.
         * This event will be published.
         * <p>
         * The {@code payload} parameter accepts either an event payload object or an {@link EventMessage}.
         * If an {@link EventMessage} is provided, the given {@code metadata} will be merged with the message's
         * existing metadata using {@link EventMessage#andMetadata(Metadata)}.
         *
         * @param payload  The event payload or {@link EventMessage} to publish.
         * @param metadata The metadata to attach to the event (merged if payload is an {@link EventMessage}).
         * @return The current Given instance, for fluent interfacing.
         */
        default Given event(Object payload, Map<String, String> metadata) {
            return event(payload, Metadata.from(metadata));
        }

        /**
         * Configures a single event with the given {@code metadata} as part of the "given" state.
         * This event will be published.
         * <p>
         * The {@code payload} parameter accepts either an event payload object or an {@link EventMessage}.
         * If an {@link EventMessage} is provided, the given {@code metadata} will be merged with the message's
         * existing metadata using {@link EventMessage#andMetadata(Metadata)}.
         *
         * @param payload  The event payload or {@link EventMessage} to publish.
         * @param metadata The metadata to attach to the event (merged if payload is an {@link EventMessage}).
         * @return The current Given instance, for fluent interfacing.
         */
        Given event(Object payload, Metadata metadata);

        /**
         * Configures the given {@code messages} as events in the "given" state. These events will be published in the
         * order they are provided.
         * <p>
         * All the {@code messages} will be processed within a single Unit of Work, meaning their processing won't be
         * affected by changes made by earlier messages passed to this method.
         *
         * @param messages The event messages to publish.
         * @return The current Given instance, for fluent interfacing.
         */
        Given events(EventMessage... messages);

        /**
         * Configures the given {@code events} as events in the "given" state. These events will be published in the
         * order they are provided.
         * <p>
         * All the {@code messages} will be processed within a single Unit of Work, meaning their processing won't be
         * affected by changes made by earlier messages passed to this method.
         *
         * @param events The lists of events to publish.
         * @return The current Given instance, for fluent interfacing.
         */
        default Given events(Object... events) {
            return events(Arrays.stream(events).toList());
        }

        /**
         * Configures the given {@code events} as events in the "given" state. These events will be published in the
         * order they are provided.
         * <p>
         * All the {@code messages} will be processed within a single Unit of Work, meaning their processing won't be
         * affected by changes made by earlier messages passed to this method.
         *
         * @param events The lists of events to publish.
         * @return The current Given instance, for fluent interfacing.
         */
        Given events(List<?> events);

        /**
         * Configures a single command as part of the "given" state. This command will be dispatched to corresponding
         * command handlers with empty metadata.
         * <p>
         * The {@code payload} parameter accepts either a command payload object or a {@link CommandMessage}.
         * If a {@link CommandMessage} is provided, it will be used directly.
         *
         * @param payload The command payload or {@link CommandMessage} to dispatch.
         * @return The current Given instance, for fluent interfacing.
         */
        default Given command(Object payload) {
            return command(payload, Metadata.emptyInstance());
        }

        /**
         * Configures a single command with the given {@code metadata} as part of the "given" state.
         * This command will be dispatched to corresponding command handlers.
         * <p>
         * The {@code payload} parameter accepts either a command payload object or a {@link CommandMessage}.
         * If a {@link CommandMessage} is provided, the given {@code metadata} will be merged with the message's
         * existing metadata using {@link CommandMessage#andMetadata(Metadata)}.
         *
         * @param payload  The command payload or {@link CommandMessage} to dispatch.
         * @param metadata The metadata to attach to the command (merged if payload is a {@link CommandMessage}).
         * @return The current Given instance, for fluent interfacing.
         */
        default Given command(Object payload, Map<String, String> metadata) {
            return command(payload, Metadata.from(metadata));
        }

        /**
         * Configures a single command with the given {@code metadata} as part of the "given" state.
         * This command will be dispatched to corresponding command handlers.
         * <p>
         * The {@code payload} parameter accepts either a command payload object or a {@link CommandMessage}.
         * If a {@link CommandMessage} is provided, the given {@code metadata} will be merged with the message's
         * existing metadata using {@link CommandMessage#andMetadata(Metadata)}.
         *
         * @param payload  The command payload or {@link CommandMessage} to dispatch.
         * @param metadata The metadata to attach to the command (merged if payload is a {@link CommandMessage}).
         * @return The current Given instance, for fluent interfacing.
         */
        Given command(Object payload, Metadata metadata);

        /**
         * Configures the given {@code messages} as commands in the "given" state.
         * <p>
         * Each message will be processed in a dedicated Unit of Work, meaning that the processing of a message will be
         * affected by the state changes made by the processing of previous messages. This behavior is in contrast to
         * the {@link Given#events} method, where all messages are processed within a single Unit of Work.
         *
         * @param messages The command messages to dispatch.
         * @return The current Given instance, for fluent interfacing.
         */
        Given commands(CommandMessage... messages);

        /**
         * Configures the given {@code commands} as commands in the "given" state. These commands will be dispatched in
         * the order they are provided in the same Unit of Work.
         * <p>
         * Each message will be processed in a dedicated Unit of Work, meaning that the processing of a message will be
         * affected by the state changes made by the processing of previous messages. This behavior is in contrast to
         * the {@link Given#events} method, where all messages are processed within a single Unit of Work.
         *
         * @param commands The command messages to dispatch.
         * @return The current Given instance, for fluent interfacing.
         */
        default Given commands(Object... commands) {
            return commands(Arrays.stream(commands).toList());
        }

        /**
         * Configures the given {@code commands} as commands in the "given" state. These commands will be dispatched in
         * the order they are provided in the same Unit of Work.
         * <p>
         * Each message will be processed in a dedicated Unit of Work, meaning that the processing of a message will be
         * affected by the state changes made by the processing of previous messages. This behavior is in contrast to
         * the {@link Given#events} method, where all messages are processed within a single Unit of Work.
         *
         * @param commands The command messages to dispatch.
         * @return The current Given instance, for fluent interfacing.
         */
        Given commands(List<?> commands);

        /**
         * Allows running custom setup steps (other than executing messages) on any component retrievable from the
         * {@link Configuration}.
         *
         * @param consumer The consumer to execute on the configuration.
         * @return The current Given instance, for fluent interfacing.
         */
        default Given execute(Consumer<Configuration> consumer) {
            return executeAsync(config -> {
                consumer.accept(config);
                return FutureUtils.emptyCompletedFuture();
            });
        }

        /**
         * Allows running custom setup steps (other than executing messages) on any component retrievable from the
         * {@link Configuration}.
         *
         * @param function The function to execute on the configuration.
         * @return The current Given instance, for fluent interfacing.
         */
        Given executeAsync(Function<Configuration, CompletableFuture<?>> function);

        /**
         * Transitions to the When phase to execute the test action.
         *
         * @return A {@link When} instance that allows executing the test.
         */
        When when();

        /**
         * Transitions to the Then phase to validate the results of the test. It skips the When phase.
         *
         * @return A {@link Then.Nothing} instance that allows validating the test results.
         */
        Then.Nothing then();
    }

    /**
     * Interface describing the operations available in the When phase of the test fixture execution. This phase is used
     * to execute the actual action being tested, typically a command.
     * <p>
     * Each operation in the phase (such as dispatching a command) is executed in its own separate Unit of Work which is
     * committed immediately after execution. This allows for building up the initial state incrementally with each
     * operation being processed independently.
     */
    interface When {

        /**
         * When-phase specific for executing a {@link CommandMessage command}.
         */
        interface Command {

            /**
             * Transitions to the Then phase to validate the results of the test.
             *
             * @return A {@link Then} instance that allows validating the test results.
             */
            Then.Command then();
        }

        /**
         * When-phase specific for publishing an {@link EventMessage event}.
         */
        interface Event {

            /**
             * Transitions to the Then phase to validate the results of the test.
             *
             * @return A {@link Then} instance that allows validating the test results.
             */
            Then.Event then();
        }

        /**
         * When-phase after no action in the When-phase.
         */
        interface Nothing {

            /**
             * Transitions to the Then phase to validate the results of the test.
             *
             * @return A {@link Then} instance that allows validating the test results.
             */
            Then.Nothing then();
        }

        /**
         * Dispatches the given command to the appropriate command handler and records all activity for
         * result validation. The command will be dispatched with empty metadata.
         * <p>
         * The {@code payload} parameter accepts either a command payload object or a {@link CommandMessage}.
         * If a {@link CommandMessage} is provided, it will be used directly.
         *
         * @param payload The command payload or {@link CommandMessage} to dispatch.
         * @return The current When instance, for fluent interfacing.
         */
        default Command command(Object payload) {
            return command(payload, new HashMap<>());
        }

        /**
         * Dispatches the given command with the provided {@code metadata} to the appropriate command
         * handler and records all activity for result validation.
         * <p>
         * The {@code payload} parameter accepts either a command payload object or a {@link CommandMessage}.
         * If a {@link CommandMessage} is provided, the given {@code metadata} will be merged with the message's
         * existing metadata using {@link CommandMessage#andMetadata(Metadata)}.
         *
         * @param payload  The command payload or {@link CommandMessage} to dispatch.
         * @param metadata The metadata to attach to the command (merged if payload is a {@link CommandMessage}).
         * @return The current When instance, for fluent interfacing.
         */
        default Command command(Object payload, Map<String, String> metadata) {
            return command(payload, Metadata.from(metadata));
        }

        /**
         * Dispatches the given command with the provided {@code metadata} to the appropriate command
         * handler and records all activity for result validation.
         * <p>
         * The {@code payload} parameter accepts either a command payload object or a {@link CommandMessage}.
         * If a {@link CommandMessage} is provided, the given {@code metadata} will be merged with the message's
         * existing metadata using {@link CommandMessage#andMetadata(Metadata)}.
         *
         * @param payload  The command payload or {@link CommandMessage} to dispatch.
         * @param metadata The metadata to attach to the command (merged if payload is a {@link CommandMessage}).
         * @return The current When instance, for fluent interfacing.
         */
        Command command(Object payload, Metadata metadata);

        /**
         * Publishes the given event to the appropriate event handler and records all activity for result validation.
         * The event will be published with empty metadata.
         * <p>
         * The {@code payload} parameter accepts either an event payload object or an {@link EventMessage}.
         * If an {@link EventMessage} is provided, it will be used directly.
         *
         * @param payload The event payload or {@link EventMessage} to publish.
         * @return The current When instance, for fluent interfacing.
         */
        default Event event(Object payload) {
            return event(payload, Metadata.emptyInstance());
        }

        /**
         * Publishes the given event with the provided {@code metadata} to the appropriate event handler
         * and records all activity for result validation.
         * <p>
         * The {@code payload} parameter accepts either an event payload object or an {@link EventMessage}.
         * If an {@link EventMessage} is provided, the given {@code metadata} will be merged with the message's
         * existing metadata using {@link EventMessage#andMetadata(Metadata)}.
         *
         * @param payload  The event payload or {@link EventMessage} to publish.
         * @param metadata The metadata to attach to the event (merged if payload is an {@link EventMessage}).
         * @return The current When instance, for fluent interfacing.
         */
        default Event event(Object payload, Map<String, String> metadata) {
            return event(payload, Metadata.from(metadata));
        }

        /**
         * Publishes the given event with the provided {@code metadata} to the appropriate event handler
         * and records all activity for result validation.
         * <p>
         * The {@code payload} parameter accepts either an event payload object or an {@link EventMessage}.
         * If an {@link EventMessage} is provided, the given {@code metadata} will be merged with the message's
         * existing metadata using {@link EventMessage#andMetadata(Metadata)}.
         *
         * @param payload  The event payload or {@link EventMessage} to publish.
         * @param metadata The metadata to attach to the event (merged if payload is an {@link EventMessage}).
         * @return The current When instance, for fluent interfacing.
         */
        Event event(Object payload, Metadata metadata);

        /**
         * Publishes the given Event Messages to the appropriate event handlers and records all activity for result
         * validation.
         *
         * @param messages The event messages to publish.
         * @return The current When instance, for fluent interfacing.
         */
        Event events(EventMessage... messages);

        /**
         * Publishes the given events to the appropriate event handlers and records all activity for result
         * validation. The events will be published with empty metadata.
         *
         * @param events The events (payloads or EventMessages) to publish.
         * @return The current When instance, for fluent interfacing.
         */
        default Event events(Object... events) {
            return events(Arrays.stream(events).toList());
        }

        /**
         * Publishes the given events to the appropriate event handlers and records all activity for result
         * validation.
         *
         * @param events The list of events to publish.
         * @return The current When instance, for fluent interfacing.
         */
        Event events(List<?> events);

        /**
         * Transitions to the Then phase to validate the results of the test. It skips the When phase.
         *
         * @return A {@link Then.Nothing} instance that allows validating the test results.
         */
        Nothing nothing();
    }

    /**
     * Interface describing the operations available in the Then phase of the test fixture execution. This phase is used
     * to validate the results of the test action executed in the When phase.
     */
    interface Then {

        /**
         * Operations available in the Then phase of the test fixture execution only if command was dispatched during
         * the When phase.
         */
        interface Command extends Message<Command> {

            /**
             * Expect a successful execution of the When phase, regardless of the actual return value.
             *
             * @return The current Then instance, for fluent interfacing.
             */
            Command success();

            /**
             * Invokes the given {@code consumer} of the command result message that has been returned during the When
             * phase, allowing for <b>any</b> form of assertion.
             *
             * @param consumer Consumes the command result. You may place your own assertions here.
             * @return The current Then instance, for fluent interfacing.
             */
            Command resultMessageSatisfies(Consumer<? super CommandResultMessage> consumer);

            /**
             * Expect the last command handler from the When phase to return the given {@code expectedPayload} after
             * execution. The actual and expected values are compared using their equals methods.
             * <p>
             * Only take commands into account that were dispatched explicitly with the {@link When#command}. Hence, do
             * not take into accounts commands dispatched as side effects of the message handlers.
             *
             * @param expectedPayload The expected result message payload of the command execution.
             * @return The current Then, for fluent interfacing.
             */
            Command resultMessagePayload(Object expectedPayload);

            /**
             * Invokes the given {@code consumer} of the command result payload that has been returned during the When
             * phase, allowing for <b>any</b> form of assertion.
             * <p>
             * <b>Note:</b> Depending on the infrastructure, the payload may require conversion (e.g., from
             * {@code byte[]} or other intermediate representations). If you need to perform assertions on a typed
             * payload, use {@link #resultMessagePayloadSatisfies(Class, Consumer)} instead, which automatically
             * converts the payload to the expected type.
             *
             * @param consumer Consumes the command result payload. You may place your own assertions here.
             * @return The current Then instance, for fluent interfacing.
             * @see #resultMessagePayloadSatisfies(Class, Consumer)
             * @deprecated Use {@link #resultMessagePayloadSatisfies(Class, Consumer)} instead, which automatically
             * handles payload conversion for distributed setups where conversion is necessary.
             */
            @Deprecated(since = "5.1.0", forRemoval = true)
            Command resultMessagePayloadSatisfies(Consumer<Object> consumer);

            /**
             * Invokes the given {@code consumer} of the command result payload that has been returned during the When
             * phase, after converting it to the expected {@code type}. This method automatically handles payload
             * conversion using the configured
             * {@link org.axonframework.messaging.core.conversion.MessageConverter}.
             *
             * @param type     The expected type of the payload.
             * @param consumer Consumes the converted command result payload. You may place your own assertions here.
             * @param <T>      The type of the payload.
             * @return The current Then instance, for fluent interfacing.
             */
            <T> Command resultMessagePayloadSatisfies(Class<T> type, Consumer<T> consumer);
        }

        /**
         * Operations available in the Then phase of the test fixture execution only if event was published during the
         * When phase.
         */
        interface Event extends Message<Event> {

            /**
             * Expect a successful execution of the When phase, no exception raised while handling the event.
             *
             * @return The current Then instance, for fluent interfacing.
             */
            Event success();
        }

        /**
         * Operations available in the Then phase of the test fixture execution only if no command or event was
         * dispatched during the When phase.
         */
        interface Nothing extends Message<Nothing> {

        }

        /**
         * Interface describing the operations available in the Then phase of the test fixture execution. It's possible
         * to assert messages produced while handling inputs in the When phase.
         * <p>
         * Message assertions exclude commands and events supplied directly through the current {@link When when-phase}.
         * Messages produced while handling those inputs remain visible, including messages with equal payloads, because
         * inputs are identified by their {@link org.axonframework.messaging.core.Message#identifier() message
         * identifier}.
         *
         * @param <T> the type of the current Then instance, for fluent interfacing; the type depends on the operation
         *            which was triggered in the When phase
         */
        interface Message<T extends Message<T>> extends MessageAssertions<T> {

            /**
             * Waits until the given {@code assertion} passes or the default timeout of 5 seconds is reached. The
             * assertion receives the current Then instance, allowing to invoke any of its assertion methods.
             *
             * @param assertion The assertion to wait for. The assertion will be invoked on the current Then instance.
             * @return The current Then instance, for fluent interfacing.
             */
            default T await(Consumer<T> assertion) {
                return await(assertion, Duration.ofSeconds(5));
            }

            /**
             * Waits until the given {@code assertion} passes or the given {@code timeout} is reached. The assertion
             * receives the current Then instance, allowing to invoke any of its assertion methods.
             *
             * @param assertion The assertion to wait for. The assertion will be invoked on the current Then instance.
             * @param timeout   The maximum time to wait for the assertion to pass. If the timeout is reached, the
             *                  assertion.
             * @return The current Then instance, for fluent interfacing.
             */
            T await(Consumer<T> assertion, Duration timeout);

            /**
             * Returns to the setup phase to continue with additional test scenarios. This allows for chaining multiple
             * test scenarios within a single test method. The same configuration from the original fixture is reused,
             * so all components are shared among the invocations.
             * <p>
             * Example usage:
             * <pre>
             * {@code
             * fixture.given()
             *        .event(new AccountCreatedEvent("account-1"))
             *        .when()
             *        .command(new WithdrawMoneyCommand("account-1", 50.00))
             *        .then()
             *        .events(new MoneyWithdrawnEvent("account-1", 50.00))
             *        .success()
             *        .and()  // Return to setup phase with same configuration
             *        .given() // Start a new scenario
             *        .event(new AccountCreatedEvent("account-2"))
             *        .when()
             *        .command(new WithdrawMoneyCommand("account-2", 30.00))
             *        .then()
             *        .events(new MoneyWithdrawnEvent("account-2", 30.00));
             * }
             * </pre>
             *
             * @return A {@link Setup} instance that allows configuring a new test scenario.
             */
            Setup and();

            /**
             * Stops the fixture, releasing any active resources, like registered handlers or pending event processing
             * tasks.
             */
            default void stop() {
                and().stop();
            }
        }

        /**
         * Interface describing the operations available in the Then phase of the test fixture execution. It's possible
         * to assert published messages from the When phase.
         *
         * @param <T> The type of the current Then instance, for fluent interfacing. The type depends on the operation
         *            which was triggered in the When phase.
         */
        interface MessageAssertions<T extends MessageAssertions<T>> {

            /**
             * Expect the given set of events to have been published during the {@link When} phase.
             * <p>
             * All events are compared for equality using a shallow equals comparison on all the fields of the events.
             * This means that all assigned values on the events' fields should have a proper equals implementation.
             * <p>
             * Note that the {@link EventMessage#identifier()} is ignored in the comparison.
             *
             * @param expectedEvents The expected events, in the exact order they are expected to be published.
             * @return The current Then instance, for fluent interfacing.
             */
            T events(Object... expectedEvents);

            /**
             * Expect the given set of event messages to have been published during the {@link When} phase.
             * <p>
             * All events are compared for equality using a shallow equals comparison on all the fields of the events.
             * This means that all assigned values on the events' fields should have a proper equals implementation.
             * Additionally, the metadata will be compared too.
             * <p>
             * Note that the {@link EventMessage#identifier()} is ignored in the comparison.
             *
             * @param expectedEvents The expected event messages, in the exact order they are expected to be published.
             * @return The current Then instance, for fluent interfacing.
             */
            T events(EventMessage... expectedEvents);

            /**
             * Invokes the given {@code consumer} of the set of event messages that have been published during the When
             * phase, allowing for <b>any</b> form of assertion.
             *
             * @param consumer Consumes the published events. You may place your own assertions here.
             * @return The current Then instance, for fluent interfacing.
             */
            T eventsSatisfy(Consumer<List<EventMessage>> consumer);

            /**
             * Allow to check if the set of event messages which have been published during the When phase match given
             * {@code predicate}.
             *
             * @param predicate The predicate to check the dispatched events against.
             * @return The current Then instance, for fluent interfacing.
             */
            T eventsMatch(Predicate<List<EventMessage>> predicate);

            /**
             * Expect no events to have been published during the {@link When} phase.
             *
             * @return The current Then instance, for fluent interfacing.
             */
            default T noEvents() {
                return events();
            }

            /**
             * Expect the given set of commands to have been dispatched during the When phase.
             * <p>
             * All commands are compared for equality using a shallow equals comparison on all the fields of the
             * commands. This means that all assigned values on the commands' fields should have a proper equals
             * implementation.
             *
             * @param expectedCommands The expected commands, in the exact order they are expected to be dispatched.
             * @return The current Then instance, for fluent interfacing.
             */
            T commands(Object... expectedCommands);

            /**
             * Expect the given set of command messages to have been dispatched during the When phase.
             * <p>
             * All commands are compared for equality using a shallow equals comparison on all the fields of the
             * commands. This means that all assigned values on the commands' fields should have a proper equals
             * implementation. Additionally, the metadata will be compared too.
             *
             * @param expectedCommands The expected command messages, in the exact order they are expected to be
             *                         dispatched.
             * @return The current Then instance, for fluent interfacing.
             */
            T commands(CommandMessage... expectedCommands);

            /**
             * Invokes the given {@code consumer} of the set of command messages that have been dispatched during the
             * When phase, allowing for <b>any</b> form of assertion. *
             *
             * @param consumer Consumes the dispatched commands. You may place your own assertions here.
             * @return The current Then instance, for fluent interfacing.
             */
            T commandsSatisfy(Consumer<List<CommandMessage>> consumer);

            /**
             * Allow to check if the set of command messages which have been dispatched during the When phase match
             * given predicate.
             *
             * @param predicate The predicate to check the dispatched commands against.
             * @return The current Then instance, for fluent interfacing.
             */
            T commandsMatch(Predicate<List<CommandMessage>> predicate);

            /**
             * Expect no command messages to have been dispatched during the When phase.
             *
             * @return The current Then instance, for fluent interfacing.
             */
            T noCommands();

            /**
             * Expect the given {@code expectedException} to occur during the When phase execution. The actual exception
             * should be exactly of that type, subclasses are not accepted.
             * <p>
             * Only take messages into account that were published explicitly with the {@link When#command} or
             * {@link When#event}. Hence, do not take into accounts messages published as side effects of other message
             * handlers present in the configuration.
             *
             * @param type The type of exception expected from the When phase execution.
             * @return The current Then instance, for fluent interfacing.
             */
            T exception(Class<? extends Throwable> type);

            /**
             * Expect the exception with given {@code type} and {@code message} to occur during the When phase
             * execution. The actual exception should be exactly of that type, subclasses are not accepted.
             * <p>
             * Only take messages into account that were published explicitly with the {@link When#command} or
             * {@link When#event}. Hence, do not take into accounts messages published as side effects of other message
             * handlers present in the configuration.
             *
             * @param type    The type of exception expected from the When phase execution.
             * @param message The message of the exception expected from the When phase execution.
             * @return The current Then instance, for fluent interfacing.
             */
            T exception(Class<? extends Throwable> type, String message);

            /**
             * Invokes the given {@code consumer} of the exception that has been throw during the When phase, allowing
             * for <b>any</b> form of assertion.
             *
             * @param consumer Consumes the thrown exception. You may place your own assertions here.
             * @return The current Then instance, for fluent interfacing.
             */
            T exceptionSatisfies(Consumer<Throwable> consumer);

            /**
             * Allows running assertions on any component retrievable from the {@link Configuration}.
             *
             * @param function The function to execute on the configuration.
             * @return The current Then instance, for fluent interfacing.
             */
            T expect(Consumer<Configuration> function);

            /**
             * Allows running assertions on any component retrievable from the {@link Configuration}.
             *
             * @param function The function to execute on the configuration.
             * @return The current Then instance, for fluent interfacing.
             */
            T expectAsync(Function<Configuration, CompletableFuture<?>> function);
        }
    }
}

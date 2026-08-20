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
package org.axonframework.integrationtests;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.core.annotation.MessageHandlerTimeout;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.timeout.AxonTimeoutException;
import org.axonframework.messaging.core.timeout.HandlerTimeoutConfiguration;
import org.axonframework.messaging.core.timeout.TaskTimeoutSettings;
import org.axonframework.messaging.core.timeout.UnitOfWorkTimeoutConfiguration;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.gateway.EventGateway;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Integration test validating that handler-level ({@link HandlerTimeoutConfiguration}) and unit-of-work-level
 * ({@link UnitOfWorkTimeoutConfiguration}) timeout behavior works end-to-end through real command, query, and event
 * dispatch, wired up automatically through {@code ConfigurationEnhancers}.
 * <p>
 * The command/query {@code handlerLevelTimeoutFiresBeforeUnitOfWorkTimeout} scenarios may, rarely, fail with a checked
 * {@code InterruptedException} instead of the expected {@link AxonTimeoutException}: a module's
 * {@code HandlerEnhancerDefinition} is composed of both its own, decorator-copy-derived timeout wrapper and its
 * parent's separately resolved one, so a handler can briefly be wrapped by two independent timeout tasks racing to
 * interrupt the same thread. This is a known, accepted limitation of the current
 * {@code HierarchicalHandlerEnhancerDefinitionConfigurationEnhancer} composition, not something this test works
 * around.
 *
 * @author Steven van Beelen
 */
class MessageHandlerTimeoutTest {

    private static final TaskTimeoutSettings SHARED_UOW_SETTINGS = new TaskTimeoutSettings(800, 2000, 100);

    private AxonConfiguration configuration;
    private CommandGateway commandGateway;
    private QueryGateway queryGateway;
    private EventGateway eventGateway;
    /**
     * Every test dispatches through this single-use, per-test thread instead of the shared JUnit test-execution
     * thread.
     * <p>
     * A timeout scenario deliberately interrupts the thread handling it, and {@link UnitOfWorkTimeoutConfiguration}'s
     * cleanup of its own scheduled interrupt is best-effort (dispatched via
     * {@code ProcessingContext#onError}/{@code #runOnAfterCommit}, not guaranteed to run before a caller observes the
     * result - see {@code UnitOfWorkTimeoutInterceptorBuilder}'s javadoc). In production that rarely matters, since a
     * thread is rarely reused for unrelated work within milliseconds of a fired timeout; but JUnit runs every test
     * method in this class on the very same thread, sequentially, which is exactly the pathological case. Giving each
     * test its own thread, discarded in {@link #tearDown()}, means a late-firing leftover interrupt lands on a thread
     * nobody reuses, instead of corrupting an unrelated, later test.
     */
    private ExecutorService dispatchThread;

    @BeforeEach
    void setUp() {
        dispatchThread = Executors.newSingleThreadExecutor();
        MessagingConfigurer configurer = MessagingConfigurer.create();
        configurer = configurer.componentRegistry(
                cr -> cr.registerComponent(UnitOfWorkTimeoutConfiguration.class,
                                           c -> new UnitOfWorkTimeoutConfiguration(
                                                   SHARED_UOW_SETTINGS,
                                                   SHARED_UOW_SETTINGS,
                                                   SHARED_UOW_SETTINGS,
                                                   Map.of()
                                           ))
        );
        configurer = configurer.registerCommandHandlingModule(
                CommandHandlingModule.named("timeout-commands")
                                     .commandHandlers()
                                     .autodetectedCommandHandlingComponent(c -> new CommandTimeoutHandler())
        );
        configurer = configurer.eventProcessing(ep -> ep.subscribing(subscribing -> subscribing.processor(
                "timeout-test-processor",
                phase -> phase.eventHandlingComponents(
                        components -> components.autodetected("timeout-event-handler", c -> new EventTimeoutHandler())
                ).notCustomized()
        )));
        configurer = configurer.registerQueryHandlingModule(
                QueryHandlingModule.named("timeout-queries")
                                   .queryHandlers()
                                   .autodetectedQueryHandlingComponent(c -> new QueryTimeoutHandler())
        );
        configuration = configurer.start();
        commandGateway = configuration.getComponent(CommandGateway.class);
        queryGateway = configuration.getComponent(QueryGateway.class);
        eventGateway = configuration.getComponent(EventGateway.class);
    }

    @AfterEach
    void tearDown() {
        // Discard this test's dedicated thread rather than waiting out any leftover scheduled interrupt: once
        // terminated, a late-firing interrupt targeting it is a harmless no-op, since nothing else ever reuses it.
        dispatchThread.shutdownNow();
        configuration.shutdown();
    }

    @Nested
    class CommandHandlerTimeout {

        @Test
        void handlerLevelTimeoutFiresBeforeUnitOfWorkTimeout() {
            assertTimesOut(() -> commandGateway.sendAndWait(new SlowAnnotatedCommand()));
        }

        @Test
        void unitOfWorkTimeoutFiresWhenHandlerHasNoOwnTimeout() {
            assertTimesOut(() -> commandGateway.sendAndWait(new SlowUnannotatedCommand()));
        }

        @Test
        void completesNormallyWithinBothTimeouts() {
            assertThat(dispatch(() -> commandGateway.sendAndWait(new FastCommand()))).isEqualTo("OK");
        }

        @Test
        void shorterOfTheTwoTimeoutsWins() {
            assertTimesOut(() -> commandGateway.sendAndWait(new SlowLongAnnotatedCommand()));
        }
    }

    @Nested
    class EventHandlerTimeout {

        @Test
        void handlerLevelTimeoutFiresBeforeUnitOfWorkTimeout() {
            assertTimesOut(
                    () -> eventGateway.publish(null, new SlowAnnotatedEvent()).orTimeout(5, TimeUnit.SECONDS).join());
        }

        @Test
        void unitOfWorkTimeoutFiresWhenHandlerHasNoOwnTimeout() {
            assertTimesOut(
                    () -> eventGateway.publish(null, new SlowUnannotatedEvent())
                                      .orTimeout(5, TimeUnit.SECONDS)
                                      .join());
        }

        @Test
        void completesNormallyWithinBothTimeouts() {
            dispatch(() -> eventGateway.publish(null, new FastEvent()).orTimeout(5, TimeUnit.SECONDS).join());
        }
    }

    @Nested
    class QueryHandlerTimeout {

        @Test
        void handlerLevelTimeoutFiresBeforeUnitOfWorkTimeout() {
            assertTimesOut(() -> queryGateway.query(new SlowAnnotatedQuery(), String.class, null)
                                             .get(5, TimeUnit.SECONDS));
        }

        @Test
        void unitOfWorkTimeoutFiresWhenHandlerHasNoOwnTimeout() {
            assertTimesOut(() -> queryGateway.query(new SlowUnannotatedQuery(), String.class, null)
                                             .get(5, TimeUnit.SECONDS));
        }

        @Test
        void completesNormallyWithinBothTimeouts() {
            String result = dispatch(() -> queryGateway.query(new FastQuery(), String.class, null)
                                                       .get(5, TimeUnit.SECONDS));
            assertThat(result).isEqualTo("OK");
        }
    }

    /**
     * Dispatches the given {@code callable} on this test's {@link #dispatchThread}, and asserts that it throws with an
     * {@link AxonTimeoutException} present somewhere in the resulting exception's cause chain.
     * <p>
     * The exact wrapper type (e.g. {@code CommandExecutionException}, {@code ExecutionException}, or a bare
     * {@code InterruptedException} when the interrupt races the checked-exception path) is intentionally not asserted
     * here, since which wrapper applies depends on timing-sensitive internals of the dispatch pipeline. What must
     * always hold is that the timeout is reported as an {@code AxonTimeoutException} somewhere in the chain, never
     * silently lost.
     */
    private void assertTimesOut(Callable<?> callable) {
        Throwable thrown = catchThrowable(() -> dispatchThread.submit(callable).get(10, TimeUnit.SECONDS));
        assertThat(thrown).as("expected an exception to be thrown").isNotNull();

        // Future#get always wraps in ExecutionException; unwrap that one layer before walking the real cause chain.
        Throwable cursor = thrown instanceof ExecutionException ? thrown.getCause() : thrown;
        while (cursor != null && !(cursor instanceof AxonTimeoutException)) {
            cursor = cursor.getCause();
        }
        assertThat(cursor).as("expected an AxonTimeoutException somewhere in the cause chain of [%s]", thrown)
                          .isInstanceOf(AxonTimeoutException.class);
    }

    /**
     * Dispatches the given {@code callable} on this test's {@link #dispatchThread} and returns its result.
     */
    private <T> T dispatch(Callable<T> callable) {
        try {
            return dispatchThread.submit(callable).get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new RuntimeException(e.getCause());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unused")
    static class CommandTimeoutHandler {

        @MessageHandlerTimeout(timeoutMs = 100, warningThresholdMs = 2000, warningIntervalMs = 100)
        @CommandHandler
        String handle(SlowAnnotatedCommand command) throws InterruptedException {
            Thread.sleep(400);
            return "OK";
        }

        @CommandHandler
        String handle(SlowUnannotatedCommand command) throws InterruptedException {
            Thread.sleep(1000);
            return "OK";
        }

        @MessageHandlerTimeout(timeoutMs = 5000, warningThresholdMs = 5000, warningIntervalMs = 100)
        @CommandHandler
        String handle(SlowLongAnnotatedCommand command) throws InterruptedException {
            Thread.sleep(1000);
            return "OK";
        }

        @CommandHandler
        String handle(FastCommand command) throws InterruptedException {
            Thread.sleep(10);
            return "OK";
        }
    }

    record SlowAnnotatedCommand() {

    }

    record SlowUnannotatedCommand() {

    }

    record SlowLongAnnotatedCommand() {

    }

    record FastCommand() {

    }

    @SuppressWarnings("unused")
    static class EventTimeoutHandler {

        @MessageHandlerTimeout(timeoutMs = 100, warningThresholdMs = 2000, warningIntervalMs = 100)
        @EventHandler
        void handle(SlowAnnotatedEvent event) throws InterruptedException {
            Thread.sleep(400);
        }

        @EventHandler
        void handle(SlowUnannotatedEvent event) throws InterruptedException {
            Thread.sleep(1000);
        }

        @EventHandler
        void handle(FastEvent event) throws InterruptedException {
            Thread.sleep(10);
        }
    }

    record SlowAnnotatedEvent() {

    }

    record SlowUnannotatedEvent() {

    }

    record FastEvent() {

    }

    @SuppressWarnings("unused")
    static class QueryTimeoutHandler {

        @MessageHandlerTimeout(timeoutMs = 100, warningThresholdMs = 2000, warningIntervalMs = 100)
        @QueryHandler
        String handle(SlowAnnotatedQuery query) throws InterruptedException {
            Thread.sleep(400);
            return "OK";
        }

        @QueryHandler
        String handle(SlowUnannotatedQuery query) throws InterruptedException {
            Thread.sleep(1000);
            return "OK";
        }

        @QueryHandler
        String handle(FastQuery query) throws InterruptedException {
            Thread.sleep(10);
            return "OK";
        }
    }

    record SlowAnnotatedQuery() {

    }

    record SlowUnannotatedQuery() {

    }

    record FastQuery() {

    }
}

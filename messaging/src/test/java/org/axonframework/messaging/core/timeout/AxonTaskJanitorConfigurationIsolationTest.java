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
package org.axonframework.messaging.core.timeout;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

/**
 * Reproduces the scenario reported in issue #5078: two independent {@link AxonConfiguration Configurations} sharing a
 * single JVM, where shutting one down must not break timeout enforcement in the other.
 * <p>
 * Prior to the fix, {@link AxonTaskJanitor#INSTANCE} was a JVM-wide {@link ScheduledExecutorService} that any
 * {@code Configuration} could shut down on behalf of every other one, causing an uncaught
 * {@link java.util.concurrent.RejectedExecutionException} to escape message handling. Each {@code Configuration} now
 * gets its own executor, defined by {@link AxonTaskJanitor#executor()}.
 *
 * @author Steven van Beelen
 */
class AxonTaskJanitorConfigurationIsolationTest {

    private AxonConfiguration configA;
    private AxonConfiguration configB;

    @AfterEach
    void tearDown() {
        if (configA != null) {
            configA.shutdown();
        }
        if (configB != null) {
            configB.shutdown();
        }
    }

    @Test
    void shuttingDownOneConfigurationDoesNotAffectTimeoutEnforcementInAnother() throws Exception {
        // given
        configA = buildConfigurationWithSlowCommandHandler();
        configB = buildConfigurationWithSlowCommandHandler();

        ScheduledExecutorService executorA =
                configA.getComponent(ScheduledExecutorService.class, AxonTaskJanitor.EXECUTOR_COMPONENT_NAME);
        ScheduledExecutorService executorB =
                configB.getComponent(ScheduledExecutorService.class, AxonTaskJanitor.EXECUTOR_COMPONENT_NAME);
        assertThat(executorA).isNotSameAs(executorB);

        // when - shutting down Configuration A must fully terminate only A's own executor
        configA.shutdown();
        await().atMost(3, TimeUnit.SECONDS).until(executorA::isTerminated);

        // then - B's executor must remain unaffected and keep enforcing timeouts
        assertThat(executorB.isShutdown()).isFalse();

        CommandBus commandBusB = configB.getComponent(CommandBus.class);
        var command = new GenericCommandMessage(new MessageType(String.class), "payload");
        CompletableFuture<?> result = commandBusB.dispatch(command, StubProcessingContext.forMessage(command));

        // no RejectedExecutionException escapes; the short timeout fires and fails the command instead
        Throwable dispatchFailure = catchThrowable(() -> result.get(2, TimeUnit.SECONDS));
        assertThat(dispatchFailure).isInstanceOfAny(ExecutionException.class, TimeoutException.class);
    }

    private AxonConfiguration buildConfigurationWithSlowCommandHandler() {
        Object slowCommandHandler = new Object() {
            @CommandHandler
            public void handle(String command) throws InterruptedException {
                Thread.sleep(5_000);
            }
        };
        HandlerTimeoutConfiguration shortCommandTimeout = new HandlerTimeoutConfiguration(
                TaskTimeoutSettings.DISABLED,
                new TaskTimeoutSettings(50, 50, 10),
                TaskTimeoutSettings.DISABLED
        );

        return MessagingConfigurer.create()
                                  .componentRegistry(cr -> cr.registerComponent(
                                          HandlerTimeoutConfiguration.class, c -> shortCommandTimeout
                                  ))
                                  .componentRegistry(cr -> cr.registerModule(
                                          CommandHandlingModule.named("slow-command-handler")
                                                                .commandHandlers()
                                                                .autodetectedCommandHandlingComponent(
                                                                        c -> slowCommandHandler
                                                                )
                                                                .build()
                                  ))
                                  .start();
    }
}

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

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.interception.annotation.CommandHandlerInterceptor;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a before-style member {@link CommandHandlerInterceptor} -- a {@code void} method with no
 * {@code MessageHandlerInterceptorChain} parameter -- invokes its target command handler exactly once.
 * <p>
 * This uses a plain {@link MessagingConfigurer} with a single {@link CommandHandlingModule}, exactly as an application
 * would wire it, without any hand-built handler-enhancer composition. The defect it guards against is real under this
 * ordinary configuration: the command handler was invoked multiple times per command while the interceptor ran once.
 */
class BeforeInterceptorSingleInvocationTest {

    private final AtomicInteger handlerRuns = new AtomicInteger();
    private final AtomicInteger interceptorRuns = new AtomicInteger();
    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        CommandHandlingModule.CommandHandlerPhase module =
                CommandHandlingModule.named("component")
                                     .commandHandlers()
                                     .autodetectedCommandHandlingComponent(config -> new Handler(handlerRuns,
                                                                                                 interceptorRuns));
        MessagingConfigurer configurer = MessagingConfigurer.create().registerCommandHandlingModule(module);
        fixture = AxonTestFixture.with(configurer);
    }

    @AfterEach
    void tearDown() {
        fixture.stop();
    }

    // given a component with a before-style @CommandHandlerInterceptor and a @CommandHandler
    // when a single command is dispatched
    // then the interceptor runs once and the command handler runs exactly once
    @Test
    void beforeInterceptorInvokesCommandHandlerExactlyOnce() {
        fixture.given()
               .noPriorActivity()
               .when()
               .command(new DoSomething("id-1"))
               .then()
               .success();

        assertThat(interceptorRuns)
                .as("interceptor should run once")
                .hasValue(1);
        assertThat(handlerRuns)
                .as("before-interceptor must invoke the command handler exactly once")
                .hasValue(1);
    }

    static class Handler {

        private final AtomicInteger handlerRuns;
        private final AtomicInteger interceptorRuns;

        Handler(AtomicInteger handlerRuns, AtomicInteger interceptorRuns) {
            this.handlerRuns = handlerRuns;
            this.interceptorRuns = interceptorRuns;
        }

        @CommandHandlerInterceptor
        void intercept() {
            interceptorRuns.incrementAndGet();
        }

        @CommandHandler
        void handle(DoSomething command) {
            handlerRuns.incrementAndGet();
        }
    }

    record DoSomething(String id) {

    }
}

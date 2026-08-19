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

package org.axonframework.integrationtests.testsuite;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.integrationtests.testsuite.infrastructure.TestInfrastructure;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.junit.jupiter.api.AfterEach;

import java.util.UUID;

/**
 * Infrastructure-agnostic base class for all integration tests in this suite.
 * <p>
 * The specific backend (AxonServer, InMemory, Postgres, …) is supplied by leaf test classes through
 * {@link #testInfrastructure()}.
 * <p>
 * Subclasses must implement:
 * <ul>
 *     <li>{@link #testInfrastructure()} — return the infrastructure strategy to use</li>
 *     <li>{@link #applicationConfigurer()} — return the domain-specific {@link ApplicationConfigurer}</li>
 * </ul>
 * and must call {@link #startApp()} (typically from a {@code @BeforeEach} method) to start the Axon configuration.
 *
 * @author Mateusz Nowak
 * @since 5.1.0
 */
@Internal
public abstract class AbstractIT {

    protected CommandGateway commandGateway;
    protected AxonConfiguration startedConfiguration;

    /**
     * Returns the {@link TestInfrastructure} for this test. Leaf classes typically return a
     * {@code private static final} instance so the infrastructure wrapper (and any heavy resources it guards, such as a
     * shared Testcontainer) is created once per leaf class:
     * <pre>{@code
     * private static final TestInfrastructure INFRASTRUCTURE = new AxonServerTestInfrastructure();
     *
     * @Override
     * protected TestInfrastructure testInfrastructure() {
     *     return INFRASTRUCTURE;
     * }
     * }</pre>
     *
     * @return the infrastructure strategy to use for this test
     */
    protected abstract TestInfrastructure testInfrastructure();

    /**
     * Returns the domain-specific {@link ApplicationConfigurer} for this test. Called from {@link #startApp()} each
     * time the Axon configuration is started.
     *
     * @return the application configurer for this test
     */
    protected abstract ApplicationConfigurer applicationConfigurer();

    /**
     * Shuts down the Axon configuration and releases infrastructure resources acquired during {@link #startApp()}. The
     * infrastructure is only stopped when {@link #startApp()} actually completed, signalled by a non-null
     * {@link #startedConfiguration}.
     */
    @AfterEach
    void tearDown() {
        if (startedConfiguration == null) {
            return;
        }
        try {
            startedConfiguration.shutdown();
        } finally {
            testInfrastructure().stop();
        }
    }

    /**
     * Starts the Axon Framework application using the configured infrastructure and domain configurer.
     * <p>
     * Subclasses must call this method (directly or via {@code super.startApp()}) from a {@code @BeforeEach} method.
     * The infrastructure's {@link TestInfrastructure#start()} is invoked first (idempotent), followed by building and
     * starting the {@link AxonConfiguration}.
     */
    protected void startApp() {
        TestInfrastructure infra = testInfrastructure();
        infra.start();
        startedConfiguration = applicationConfigurer()
                .componentRegistry(infra::configureInfrastructure)
                .start();
        commandGateway = startedConfiguration.getComponent(CommandGateway.class);
    }

    /**
     * Purges persisted data via the current {@link TestInfrastructure}. Opt-in — only tests that require a clean
     * initial state (e.g. event-replay tests) should call this method.
     */
    protected void purgeData() {
        testInfrastructure().purgeData();
    }

    /**
     * Creates a unique ID string with the given {@code prefix}, suitable for use as an aggregate or entity identifier
     * in tests.
     *
     * @param prefix a human-readable prefix for the generated ID
     * @return a unique identifier string
     */
    protected static String createId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}

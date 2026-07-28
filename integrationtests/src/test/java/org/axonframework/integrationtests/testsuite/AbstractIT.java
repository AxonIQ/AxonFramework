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
import org.axonframework.integrationtests.testsuite.infrastructure.TestInfrastructures;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.junit.jupiter.api.AfterEach;

import java.util.UUID;

/**
 * Infrastructure-agnostic base class for all integration tests in this suite.
 * <p>
 * The store the suite is driven against is a <b>run-time selection</b>, resolved once by
 * {@link org.axonframework.integrationtests.testsuite.infrastructure.TestInfrastructures#selected()} from the
 * {@code hunt.backend} system property and defaulting to the in-memory components, so the same test classes produce a
 * per-backend verdict by being run once per backend rather than by being duplicated per backend. A test that genuinely
 * needs one particular store may still override {@link #testInfrastructure()}.
 * <p>
 * Subclasses must implement:
 * <ul>
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
     * Returns the {@link TestInfrastructure} this test is driven against, which the run selects rather than the class.
     * <p>
     * <b>Why this is not abstract any more.</b> A leaf class per suite per backend means a new file for every suite each
     * time a store is added, and the suite's extensibility charter forbids exactly that: adding a backend must inherit
     * every existing test with no new per-test code. Resolving the store from {@code hunt.backend} gives the same test
     * classes a verdict per store when they are run once per store, and gives them the in-memory components -- and no
     * container -- when the property is not set.
     * <p>
     * Override it only for a test that is about one particular store and cannot mean anything on another.
     *
     * @return the infrastructure strategy for this run
     */
    protected TestInfrastructure testInfrastructure() {
        return TestInfrastructures.selected();
    }

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

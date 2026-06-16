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

package org.axonframework.integrationtests.testsuite.infrastructure;

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.configuration.ComponentRegistry;

/**
 * Strategy interface that encapsulates the lifecycle and configuration of a specific test infrastructure backend (e.g.
 * AxonServer via Testcontainers, pure in-memory, JDBC, etc.).
 * <p>
 * An implementation is provided by each concrete leaf test class via
 * {@link org.axonframework.integrationtests.testsuite.AbstractIT#testInfrastructure()}. Leaf classes
 * typically return a {@code private static final} singleton, so {@link #start()} and {@link #stop()} are guaranteed to
 * receive the same object across test methods.
 *
 * @author Mateusz Nowak
 * @since 5.1.0
 */
@Internal
public interface TestInfrastructure {

    /**
     * Start the infrastructure (e.g. launch a Testcontainer). Must be idempotent — calling {@code start()} on an
     * already-running infrastructure must be a safe no-op.
     */
    void start();

    /**
     * Register infrastructure-specific components or disable unwanted enhancers in the given {@code registry}. Called
     * once per test, just before the {@link org.axonframework.common.configuration.AxonConfiguration} is started.
     *
     * @param registry the registry to configure
     */
    void configureInfrastructure(ComponentRegistry registry);

    /**
     * Purge persisted data so the next test starts with a clean slate. Opt-in — only tests that require a clean initial
     * state need to call {@link org.axonframework.integrationtests.testsuite.AbstractIT#purgeData()}.
     * <p>
     * For in-memory backends this is typically a no-op because the configuration is recreated per test anyway.
     */
    void purgeData();

    /**
     * Release resources acquired during {@link #start()}. Called from the base class's {@code @AfterEach} after the
     * {@link org.axonframework.common.configuration.AxonConfiguration} has been shut down.
     * <p>
     * For shared/reused containers (e.g. {@code withReuse(true)} Testcontainers), this is typically a no-op —
     * Testcontainers + Ryuk handle JVM-exit cleanup. The hook exists so future backends (e.g. Postgres with per-suite
     * schema isolation) have a defined place to release resources.
     */
    void stop();
}

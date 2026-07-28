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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place a run decides which store the whole test suite is driven against.
 * <p>
 * <b>The backend is a run-time selection, not a class.</b> A leaf class per suite per backend would mean a new file for
 * every suite each time a store is added, which is exactly the duplication the suite's extensibility charter exists to
 * forbid: adding a backend must inherit every existing test without any new per-test code. So the suite has one
 * resolution point, every test class inherits it, and the per-backend verdict vector comes from running the same classes
 * once per backend -- one job per store, one set of test classes.
 * <p>
 * Select the store with {@code -Dhunt.backend=<name>}:
 * <pre>{@code
 * ./mvnw -Pintegration-test -pl integrationtests verify                                  # in-memory, no Docker
 * ./mvnw -Pintegration-test -pl integrationtests verify -Dhunt.backend=postgres-jpa      # real PostgreSQL
 * ./mvnw -Pintegration-test -pl integrationtests verify -Dhunt.backend=postgres-jpa-split-tokens
 * ./mvnw -Pintegration-test -pl integrationtests verify -Dhunt.backend=axonserver         # real Axon Server
 * }</pre>
 * <b>The default is in-memory, so a build that says nothing starts no container.</b> That is not a convenience: a suite
 * whose default needs a Docker daemon stops being runnable on a machine that has none, and the container tier has to be
 * something a run opts into.
 * <p>
 * <b>One instance per backend name, for the whole virtual machine.</b> {@code TestInfrastructure.start()} and
 * {@code stop()} are called around every test method rather than around the class, so an infrastructure that held a
 * container or a connection pool per instance would start and stop one per test. Caching by name gives every test class
 * in a run the same instance, which is what makes {@code start()} idempotent in practice as well as in contract.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
@Internal
public final class TestInfrastructures {

    /**
     * The system property naming the store the run drives.
     */
    public static final String BACKEND_PROPERTY = "hunt.backend";

    /**
     * The store a run drives when it does not say, chosen so that the default build needs nothing installed.
     */
    public static final String IN_MEMORY = "in-memory";

    /**
     * PostgreSQL through the aggregate-based JPA engine, with the token store in the same database and transaction.
     */
    public static final String POSTGRES_JPA = "postgres-jpa";

    /**
     * PostgreSQL through the aggregate-based JPA engine, with the token store in a database and transaction of its own.
     */
    public static final String POSTGRES_JPA_SPLIT_TOKENS = "postgres-jpa-split-tokens";

    /**
     * A real Axon Server, reached through the published connector: the only persistent store here that speaks the
     * Dynamic Consistency Boundary protocol.
     * <p>
     * One method of its storage engine is supplied by the harness rather than by the connector, because no published
     * connector implements it. See {@link AxonServerTestInfrastructure} for the arm's exact label and
     * {@code formal/CONNECTOR-COMPATIBILITY.md} for which connector versions are usable.
     */
    public static final String AXON_SERVER = "axonserver";

    private static final Map<String, TestInfrastructure> PER_BACKEND = new ConcurrentHashMap<>();

    private TestInfrastructures() {
        // Utility class.
    }

    /**
     * Returns the name of the store this run drives.
     *
     * @return the value of {@value #BACKEND_PROPERTY}, or {@value #IN_MEMORY} when it is not set
     */
    public static String selectedName() {
        String requested = System.getProperty(BACKEND_PROPERTY);
        return requested == null || requested.isBlank()
                ? IN_MEMORY
                : requested.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the infrastructure this run drives, creating it once per backend name.
     *
     * @return the selected infrastructure
     * @throws IllegalArgumentException if the run named a store that does not exist, so that a typo fails the run
     *                                  instead of silently measuring the default
     */
    public static TestInfrastructure selected() {
        return PER_BACKEND.computeIfAbsent(selectedName(), TestInfrastructures::create);
    }

    private static TestInfrastructure create(String name) {
        return switch (name) {
            case IN_MEMORY -> new InMemoryTestInfrastructure();
            case POSTGRES_JPA -> PostgresTestInfrastructure.sharedTransactionalResource();
            case POSTGRES_JPA_SPLIT_TOKENS -> PostgresTestInfrastructure.separateTokenDatabase();
            case AXON_SERVER -> new AxonServerTestInfrastructure();
            default -> throw new IllegalArgumentException(
                    "No test infrastructure named [" + name + "] exists; known names are "
                            + List.of(IN_MEMORY, POSTGRES_JPA, POSTGRES_JPA_SPLIT_TOKENS, AXON_SERVER) + ".");
        };
    }

    /**
     * Indicates whether the selected store speaks the Dynamic Consistency Boundary protocol.
     * <p>
     * A test whose assertions are about a consistency boundary spanning several tags is <b>not applicable</b> on a store
     * that has no such thing, and has to say so rather than pass quietly: the only aggregate-based engine in this tree
     * accepts one tag per event and reads it as an aggregate identifier. Two stores here do speak it -- the in-heap
     * engine and Axon Server -- and the second is the only one of the two whose events outlive the process. Use it with
     * {@code assumeTrue(TestInfrastructures.selectedSpeaksDcb(), "...")} so the skip carries the reason into the report.
     *
     * @return {@code true} when an append condition is a boundary over tags and a marker on the selected store
     */
    public static boolean selectedSpeaksDcb() {
        return IN_MEMORY.equals(selectedName()) || AXON_SERVER.equals(selectedName());
    }
}

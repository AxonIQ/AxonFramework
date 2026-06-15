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

package org.axonframework.examples.demo.coursecatalog.catalog.chain;

import tools.jackson.databind.JsonNode;
import org.axonframework.examples.demo.coursecatalog.catalog.CourseCatalogMessageNames;
import org.axonframework.examples.demo.coursecatalog.catalog.testutil.ChainTester;
import org.axonframework.examples.demo.coursecatalog.catalog.testutil.JsonAssertions;
import org.axonframework.examples.demo.coursecatalog.catalog.transformations.CourseCatalogTransformations;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ChainConcurrencyTest {

    private static final int THREADS = 8;
    private static final int ITERATIONS_PER_THREAD = 10_000;

    @Test
    void multiHopChainProducesIdenticalOutputAcrossThreads() {
        // given
        JsonNode expectedV3 = JsonAssertions.loadJson("/transformations/coursepublished/v3.json");
        AtomicReference<@Nullable JsonNode> firstObservedPayload = new AtomicReference<>();

        // when
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            CompletableFuture<?>[] futures = new CompletableFuture[THREADS];
            for (int t = 0; t < THREADS; t++) {
                futures[t] = CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                        EventMessage output = ChainTester.forChain(CourseCatalogTransformations.chain())
                                                         .given()
                                                         .messageType(CourseCatalogMessageNames.COURSE_PUBLISHED, "1.0.0")
                                                         .payloadFromResource("/transformations/coursepublished/v1.json")
                                                         .when()
                                                         .then()
                                                         .output();
                        JsonNode observed = JsonAssertions.toJsonTree(
                                Objects.requireNonNull(output.payload(), "chain produced a null payload"));
                        firstObservedPayload.compareAndSet(null, observed);
                        if (!observed.equals(expectedV3)) {
                            throw new AssertionError("payload drifted under concurrency: " + observed);
                        }
                    }
                }, executor);
            }
            CompletableFuture.allOf(futures).orTimeout(60, TimeUnit.SECONDS).join();
        } finally {
            executor.shutdownNow();
        }

        // then
        assertThat(firstObservedPayload.get()).isEqualTo(expectedV3);
    }
}

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

package org.axonframework.hunt.harness;

import org.axonframework.hunt.scenario.HuntScenarios;
import org.axonframework.hunt.scenario.Scenario;
import org.axonframework.hunt.scenario.ScenarioResult;
import org.axonframework.hunt.scenario.ScenarioRunner;
import org.axonframework.hunt.scenario.Tier;
import org.axonframework.hunt.scenario.Verdict;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.UnableToRetrieveIdentifierException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Establishes two things about the backend whose token store really arbitrates a claim.
 * <p>
 * First, that adding it cost nothing: a scenario written before it existed runs against it by substituting a name,
 * with no edit to the scenario itself. That inheritance is the entire argument for having a backend seam, and a seam
 * nobody has crossed is a claim rather than a property.
 * <p>
 * Second, that several processors starting against one such store at the same instant do not all come up. That is
 * finding F-9, and the case below is its expected-gap test: it passes while the defect is there and flips the day it
 * is fixed.
 *
 * @author Stefan Dragisic
 * @since 5.3.0
 */
class ClaimCapableBackendTest {

    @Nested
    class AScenarioWrittenBeforeItExisted {

        @Test
        void runsAgainstItWithNoEditToTheScenario() {
            // given a scenario this build ships, pointed at the new backend and otherwise untouched
            Scenario original = HuntScenarios.appendRejectedAfterMarker();
            Scenario moved = original.onBackend(HsqldbTokenStoreBackend.NAME);

            // when it is run there
            ScenarioResult result = ScenarioRunner.run(moved, Tier.SMOKE, moved.seed(),
                                                       ScenarioRunner.historyDirectory(
                                                               Path.of("target", "hunt-histories", "backend-swap")));
            System.out.println(result);

            // then everything except the store's name is the scenario that was already there, and the verdict holds
            assertThat(moved.id()).isEqualTo(original.id());
            assertThat(moved.workload()).isSameAs(original.workload());
            assertThat(moved.oracles()).isEqualTo(original.oracles());
            assertThat(moved.backend()).isEqualTo(HsqldbTokenStoreBackend.NAME);
            assertThat(result.verdict()).isEqualTo(Verdict.PASS);
        }

        @Test
        void isDiscoveredAlongsideTheStoreThatArbitratesNothing() {
            // given the registered backends
            List<HuntBackend> backends = HuntBackend.discover();

            // when they are asked what they do about ownership
            // then both are present, and only one of them claims to arbitrate anything
            assertThat(backends).extracting(HuntBackend::name)
                                .contains(InMemoryHuntBackend.NAME, HsqldbTokenStoreBackend.NAME);
            assertThat(HuntBackend.byName(InMemoryHuntBackend.NAME).arbitratesTokenClaims()).isFalse();
            assertThat(HuntBackend.byName(HsqldbTokenStoreBackend.NAME).arbitratesTokenClaims()).isTrue();
        }
    }

    @Nested
    class SeveralProcessorsStartingAtOnceAgainstOneStore {

        /**
         * Documents finding F-9. Starting a processor asks the token store for its identifier, and a JDBC token store
         * creates that row the first time anybody asks. Several of them asking at the same instant means several
         * inserts of the same primary key: one wins, the rest get a constraint violation which the store turns into
         * an exception instead of re-reading the row that now exists, and their processors fail to start.
         * <p>
         * The assertion is deliberately the wrong way round. It records what the framework does today, so it goes red
         * the day a concurrent boot stops costing instances, and that is the signal to close the finding rather than
         * a test to repair.
         */
        @Test
        void doNotAllSucceedInResolvingItsIdentifier() {
            // given one store, and four nodes about to ask it for its identifier at the same instant
            HuntBackend backend = HuntBackend.byName(HsqldbTokenStoreBackend.NAME);
            int nodeCount = 4;
            try (TokenStores stores = backend.createTokenStores("f9-" + System.nanoTime(), Duration.ofSeconds(10))) {
                CountDownLatch release = new CountDownLatch(1);
                CountDownLatch ready = new CountDownLatch(nodeCount);
                AtomicInteger identifierFailures = new AtomicInteger();
                List<CompletableFuture<Void>> attempts = new ArrayList<>();

                for (int index = 0; index < nodeCount; index++) {
                    var store = stores.forNode("node-" + index);
                    CompletableFuture<Void> attempt = new CompletableFuture<>();
                    attempts.add(attempt);
                    Thread thread = new Thread(() -> {
                        ready.countDown();
                        try {
                            release.await();
                            store.retrieveStorageIdentifier(null).orTimeout(30, TimeUnit.SECONDS).join();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (RuntimeException e) {
                            if (rootCause(e) instanceof UnableToRetrieveIdentifierException) {
                                identifierFailures.incrementAndGet();
                            }
                        }
                        attempt.complete(null);
                    });
                    thread.setDaemon(true);
                    thread.start();
                }

                // when they are all released together
                assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new))
                                 .orTimeout(60, TimeUnit.SECONDS)
                                 .join();
                System.out.println("F-9: " + identifierFailures.get() + " of " + nodeCount
                                           + " simultaneous storage-identifier resolutions failed");

                // then at least one of them failed, which is what makes a simultaneous deployment lose instances
                assertThat(identifierFailures.get()).isPositive();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the boot barrier.", e);
            }
        }

        private static Throwable rootCause(Throwable failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
                if (cause instanceof UnableToRetrieveIdentifierException) {
                    return cause;
                }
            }
            return cause;
        }
    }
}

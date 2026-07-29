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

package org.axonframework.eventsourcing.eventstore.jpa;

import org.axonframework.eventsourcing.eventstore.EventCoordinator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the polling lifecycle of the {@link JpaPollingEventCoordinator}.
 *
 * @author Stefan Dragisic
 */
class JpaPollingEventCoordinatorTest {

    private static final Duration POLLING_INTERVAL = Duration.ofMillis(10);

    // Failing to hand out an EntityManager makes every poll throw, which the coordinator logs and
    // recovers from, so the loop reaches the callback without needing a database.
    private final JpaPollingEventCoordinator testSubject = new JpaPollingEventCoordinator(
            () -> {
                throw new IllegalStateException("no EntityManager in this test");
            },
            POLLING_INTERVAL
    );

    @Nested
    class Terminate {

        @Test
        void returnsWhenTheCallbackSwallowedTheTerminatingInterrupt() throws InterruptedException {
            // given a callback that consumes the interrupt without rethrowing it, the way a JDBC call
            // or an uninterruptible lock does
            CountDownLatch insideCallback = new CountDownLatch(1);
            EventCoordinator.Handle handle = testSubject.startCoordination(() -> {
                insideCallback.countDown();
                try {
                    Thread.sleep(Duration.ofDays(1));
                } catch (InterruptedException e) {
                    // Swallowed on purpose: the interrupt that asked the poller to stop is now lost.
                }
            });
            assertThat(insideCallback.await(5, TimeUnit.SECONDS)).isTrue();

            // when the coordination is terminated while that callback is running
            CompletableFuture<Void> termination = CompletableFuture.runAsync(handle::terminate);

            // then it still stops, because the polling loop does not rely on the interrupt surviving
            assertThat(termination).succeedsWithin(Duration.ofSeconds(10));
        }
    }
}

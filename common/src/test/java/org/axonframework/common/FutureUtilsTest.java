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

package org.axonframework.common;


import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating the {@link FutureUtils}.
 *
 * @author Jan Galinski
 */
class FutureUtilsTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void runFailingReturnsSuccessfully(boolean expectFailure) {
        Function<Boolean, CompletableFuture<Boolean>> fn = value -> {
            Assert.isTrue(value, () -> "value must be true");
            return CompletableFuture.completedFuture(true);
        };

        var result = FutureUtils.runFailing(() -> fn.apply(expectFailure));

        if (expectFailure) {
            assertThat(result).isCompletedWithValue(true);
        } else {
            assertThatThrownBy(() -> FutureUtils.joinAndUnwrap(result))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("value must be true");
        }
    }

    @Nested
    class JoinAndUnwrapWithTimeout {

        @Test
        void completedFutureReturnsImmediately() {
            // given
            CompletableFuture<String> future = CompletableFuture.completedFuture("result");

            // when
            String result = FutureUtils.joinAndUnwrap(future, Duration.ofSeconds(1));

            // then
            assertThat(result).isEqualTo("result");
        }

        @Test
        void failedFutureThrowsCauseDirectly() {
            // given
            CompletableFuture<String> future = CompletableFuture.failedFuture(
                    new IllegalStateException("test failure")
            );

            // when / then
            assertThatThrownBy(() -> FutureUtils.joinAndUnwrap(future, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("test failure");
        }

        @Test
        void incompleteFutureThrowsTimeoutException() {
            // given
            CompletableFuture<String> future = new CompletableFuture<>();

            // when / then
            assertThatThrownBy(() -> FutureUtils.joinAndUnwrap(future, Duration.ofMillis(50)))
                    .isInstanceOf(TimeoutException.class)
                    .hasMessageContaining("Future did not complete within");
        }

        @Test
        void futureCompletingBeforeTimeoutReturnsNormally() {
            // given
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "async result";
            });

            // when
            String result = FutureUtils.joinAndUnwrap(future, Duration.ofSeconds(5));

            // then
            assertThat(result).isEqualTo("async result");
        }

        @Test
        void nullResultCompletedFutureReturnsNull() {
            // given
            CompletableFuture<Void> future = CompletableFuture.completedFuture(null);

            // when
            Void result = FutureUtils.joinAndUnwrap(future, Duration.ofSeconds(1));

            // then
            assertThat(result).isNull();
        }
    }

    @Nested
    class AllOrEmpty {

        @Test
        void emptyListResultsInCompletedFuture() {
            // given
            List<CompletableFuture<Void>> futures = List.of();

            // when
            CompletableFuture<Void> result = FutureUtils.allOrEmpty(futures);

            // then
            assertThat(result).isCompletedWithValue(null);
        }

        @Test
        void completesOnlyAfterEveryFutureCompleted() {
            // given
            CompletableFuture<Void> first = new CompletableFuture<>();
            CompletableFuture<Void> second = new CompletableFuture<>();

            // when
            CompletableFuture<Void> result = FutureUtils.allOrEmpty(List.of(first, second));

            // then
            assertThat(result).isNotDone();
            first.complete(null);
            assertThat(result).isNotDone();
            second.complete(null);
            assertThat(result).isCompletedWithValue(null);
        }

        @Test
        void completedFuturesResultInCompletedFuture() {
            // given
            List<CompletableFuture<Void>> futures = List.of(FutureUtils.emptyCompletedFuture(),
                                                           FutureUtils.emptyCompletedFuture());

            // when
            CompletableFuture<Void> result = FutureUtils.allOrEmpty(futures);

            // then
            assertThat(result).isCompletedWithValue(null);
        }

        @Test
        void failingFutureResultsInExceptionallyCompletedFuture() {
            // given
            IllegalStateException expected = new IllegalStateException("first failed");
            CompletableFuture<Void> failing = CompletableFuture.failedFuture(expected);
            CompletableFuture<Void> succeeding = FutureUtils.emptyCompletedFuture();

            // when
            CompletableFuture<Void> result = FutureUtils.allOrEmpty(List.of(failing, succeeding));

            // then
            assertThat(result).isCompletedExceptionally();
            assertThatThrownBy(() -> FutureUtils.joinAndUnwrap(result, Duration.ofSeconds(1)))
                    .isSameAs(expected);
        }
    }
}
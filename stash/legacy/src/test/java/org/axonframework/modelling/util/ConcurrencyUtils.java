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

package org.axonframework.modelling.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Utility methods related to testing concurency
 */
public abstract class ConcurrencyUtils {

    /**
     * Will execute the runnable as many times as the given {@code threadCount} and assert none caused exceptions. The
     * used runnable should finish fast, not longer than a few seconds.
     *
     * @param threadCount the number of times of invocations at the 'same' time
     * @param runnable    something that is expected to be run concurrently
     */
    public static void testConcurrent(int threadCount, Runnable runnable) {
        ExecutorService service = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCounter = new AtomicInteger();
        List<Exception> exceptions = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            service.submit(() -> {
                try {
                    runnable.run();
                    successCounter.incrementAndGet();
                } catch (Exception e) {
                    exceptions.add(e);
                }
            });
        }
        service.shutdown();
        try {
            service.awaitTermination(5L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            exceptions.add(e);
        }
        assertEquals(Collections.emptyList(), exceptions);
        assertEquals(threadCount, successCounter.get(), "Not all threads have completed successfully");
    }

    private ConcurrencyUtils() {

    }
}

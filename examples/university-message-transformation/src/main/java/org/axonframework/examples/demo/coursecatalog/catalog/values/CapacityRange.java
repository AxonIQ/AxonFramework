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

package org.axonframework.examples.demo.coursecatalog.catalog.values;

/**
 * Inclusive minimum and maximum enrolment a course accepts. Current shape (v3) of what
 * was historically a single {@code capacity} integer in earlier versions.
 *
 * @param min lower bound; must be non-negative and not exceed {@code max}
 * @param max upper bound; must be greater than or equal to {@code min}
 */
public record CapacityRange(int min, int max) {

    /**
     * @param min lower bound
     * @param max upper bound
     * @throws IllegalArgumentException if {@code min} is negative or {@code min > max}
     */
    public CapacityRange {
        if (min < 0) {
            throw new IllegalArgumentException("min capacity cannot be negative: " + min);
        }
        if (max < min) {
            throw new IllegalArgumentException("max capacity (" + max + ") cannot be less than min (" + min + ")");
        }
    }
}

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

package org.axonframework.migration.find;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Cross-file accumulator state for {@link ExploreAxon4Aggregates}. The scanner visitor
 * populates this from every Java and Kotlin class declaration plus a small set of
 * method invocations ({@code configureAggregate}, {@code withSubtypes}). The merge step
 * (run from {@code generate}) decides which {@link #classIndex} entries become
 * aggregates and resolves polymorphism roles.
 *
 * @since 5.2.0
 */
class AggregateInventory {

    /**
     * All Java and Kotlin top-level class declarations seen during scan, keyed by FQN.
     * Entries are kept for non-aggregate classes too because Pass B
     * ({@code configureAggregate(X.class)}) needs to resolve {@code X} to its source file
     * even when {@code X} is not annotated with {@code @Aggregate}.
     */
    final Map<String, AggregateRecord> classIndex = new LinkedHashMap<>();

    /**
     * FQNs of classes carrying {@code @Aggregate} (Pass A). Subset of {@link #classIndex}.
     */
    final Set<String> annotated = new LinkedHashSet<>();

    /**
     * FQNs referenced by {@code configureAggregate(X.class)} or {@code configureAggregate<X>(…)}
     * (Pass B). May include FQNs absent from {@link #classIndex} when the class lives outside
     * the scanned source set.
     */
    final Set<String> configured = new LinkedHashSet<>();

    /**
     * Polymorphism Signal 2: maps a polymorphic-parent FQN to its child FQNs as declared by
     * {@code configureAggregate(Parent.class).withSubtypes(Child1.class, Child2.class)}.
     * Multiple parents may appear if the codebase wires several polymorphic aggregates.
     */
    final Map<String, Set<String>> withSubtypesMap = new LinkedHashMap<>();
}

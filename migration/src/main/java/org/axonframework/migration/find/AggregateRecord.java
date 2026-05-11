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

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-class scratch record populated during the {@link ExploreAxon4Aggregates} scanner phase
 * and finalised during merge. One instance per Java or Kotlin class seen by the scanner —
 * not all of these instances become aggregates; the merge phase decides which records are
 * promoted to the inventory based on whether they carry {@code @Aggregate}, are referenced
 * by {@code configureAggregate(...)}, or participate in a {@code withSubtypes(...)} chain.
 * <p>
 * Mutable by design: scanner visitors set basic identity fields on first sighting; later
 * passes (and the merge step) augment {@code discovery}, polymorphism fields, and
 * {@code notes}. The merge phase is the single writer that flips {@code isAggregate}.
 *
 * @since 5.2.0
 */
class AggregateRecord {

    String fqn;
    String packageName;
    String simpleName;
    String sourcePath;
    String language;

    boolean isAggregate;
    final Set<String> discovery = new LinkedHashSet<>();

    String configStyle;
    String persistence;

    boolean multiEntity;
    boolean deadline;
    boolean createNew;
    boolean polymorphic;
    String polymorphicRole;
    String polymorphicParent;
    boolean hasSnapshotTrigger;
    boolean hasCache;
    boolean hasRevision;
    boolean hasAggregateVersion;
    boolean hasCreationPolicy;
    boolean jpaEntity;

    String parentFqn;

    final Set<String> imports = new TreeSet<>();
    final Set<String> notes = new LinkedHashSet<>();

    /**
     * FQNs of the first parameter type of every {@code @CommandHandler} method declared on
     * this class — the command vocabulary the aggregate accepts. Insertion-ordered so the
     * emitted YAML is stable.
     */
    final Set<String> commands = new LinkedHashSet<>();

    /**
     * FQNs of the first parameter type of every {@code @EventHandler} or
     * {@code @EventSourcingHandler} method declared on this class — the event vocabulary
     * the aggregate observes. Both annotations are folded into one list because on AF4
     * aggregates {@code @EventSourcingHandler} is the dominant form (it rebuilds state from
     * past events) while {@code @EventHandler} is occasional; for inventory purposes the
     * caller usually wants the union.
     */
    final Set<String> events = new LinkedHashSet<>();
}

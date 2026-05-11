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

import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

/**
 * OpenRewrite data table emitted by {@link ExploreAxon4Aggregates}: one row per detected
 * aggregate, flattening the same data the recipe writes per-class to
 * {@code .axon4-explore/components/aggregate@*.yaml}. The table lands at
 * {@code <project>/target/rewrite/datatables/<timestamp>/org.axonframework.migration.find.AggregateFeaturesTable.csv}
 * when the user passes {@code -Drewrite.exportDatatables=true} to {@code rewrite:run}.
 *
 * @since 5.2.0
 */
public class AggregateFeaturesTable extends DataTable<AggregateFeaturesTable.Row> {

    public AggregateFeaturesTable(Recipe recipe) {
        super(recipe,
              "Axon 4 aggregate features inventory",
              "Per-aggregate flat view: discovery signals, persistence/config style, polymorphism role, and feature flags.");
    }

    public record Row(
            @Column(displayName = "FQCN",
                    description = "Fully-qualified class name of the aggregate.")
            String fqcn,
            @Column(displayName = "Package",
                    description = "Package name.")
            String packageName,
            @Column(displayName = "Language",
                    description = "Source language: `java` or `kotlin`.")
            String language,
            @Column(displayName = "Source path",
                    description = "Path to the source file relative to the project root.")
            String sourcePath,
            @Column(displayName = "Discovery",
                    description = "Comma-separated subset of `@Aggregate`, `configureAggregate`.")
            String discovery,
            @Column(displayName = "Config style",
                    description = "`spring` if `@Aggregate` is present, else `non-spring`.")
            String configStyle,
            @Column(displayName = "Persistence",
                    description = "`event-sourcing` if any `@EventSourcingHandler` is present, else `state-stored`.")
            String persistence,
            @Column(displayName = "Multi-entity",
                    description = "True if any field carries `@AggregateMember`.")
            boolean multiEntity,
            @Column(displayName = "Deadline",
                    description = "True if any method carries `@DeadlineHandler`.")
            boolean deadline,
            @Column(displayName = "Create new",
                    description = "True if any method invocation is named `createNew`.")
            boolean createNew,
            @Column(displayName = "Polymorphic",
                    description = "True if Signal 1 (extends-chain) or Signal 2 (`withSubtypes`) flagged this class.")
            boolean polymorphic,
            @Column(displayName = "Polymorphic role",
                    description = "`parent`, `child`, or empty.")
            String polymorphicRole,
            @Column(displayName = "Polymorphic parent",
                    description = "FQN of the polymorphic parent if role is `child`, else empty.")
            String polymorphicParent,
            @Column(displayName = "Has snapshot trigger",
                    description = "True if the `@Aggregate` annotation has a `snapshotTriggerDefinition` argument.")
            boolean hasSnapshotTrigger,
            @Column(displayName = "Has cache",
                    description = "True if the `@Aggregate` annotation has a `cache` argument.")
            boolean hasCache,
            @Column(displayName = "Has revision",
                    description = "True if the class carries `@Revision`.")
            boolean hasRevision,
            @Column(displayName = "Has aggregate version",
                    description = "True if any field carries `@AggregateVersion`.")
            boolean hasAggregateVersion,
            @Column(displayName = "Has creation policy",
                    description = "True if any method carries `@CreationPolicy`.")
            boolean hasCreationPolicy,
            @Column(displayName = "JPA entity",
                    description = "True if the class carries `@jakarta.persistence.Entity` or `@javax.persistence.Entity`.")
            boolean jpaEntity,
            @Column(displayName = "Commands",
                    description = "Comma-separated FQNs of the first parameter type of every `@CommandHandler` method on this class.")
            String commands,
            @Column(displayName = "Events",
                    description = "Comma-separated FQNs of the first parameter type of every `@EventHandler` or `@EventSourcingHandler` method on this class.")
            String events,
            @Column(displayName = "Notes",
                    description = "Newline-separated transparency notes about ambiguous detections.")
            String notes
    ) {
    }
}

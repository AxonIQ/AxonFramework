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

package org.axonframework.migration;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.kotlin.Assertions.kotlin;

/**
 * Kotlin-source tests for {@link MigrateSnapshotTriggerDefinitionToAnnotation}.
 */
class MigrateSnapshotTriggerDefinitionToAnnotationKotlinTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateSnapshotTriggerDefinitionToAnnotation())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void migratesEventCountBeanAndAggregateInSameFile() {
        rewriteRun(
                kotlin(
                        """
                        package com.example

                        import org.axonframework.eventsourcing.EventCountSnapshotTriggerDefinition
                        import org.axonframework.eventsourcing.SnapshotTriggerDefinition
                        import org.axonframework.eventsourcing.Snapshotter
                        import org.axonframework.spring.stereotype.Aggregate
                        import org.springframework.context.annotation.Bean
                        import org.springframework.context.annotation.Configuration

                        @Aggregate(snapshotTriggerDefinition = "myTrigger")
                        class MyAggregate

                        @Configuration
                        class MyConfig {
                            @Bean
                            fun myTrigger(snapshotter: Snapshotter): SnapshotTriggerDefinition {
                                return EventCountSnapshotTriggerDefinition(snapshotter, 50)
                            }
                        }
                        """,
                        """
                        package com.example

                        import org.axonframework.eventsourcing.annotation.Snapshotting
                        import org.axonframework.spring.stereotype.Aggregate
                        import org.springframework.context.annotation.Configuration

                        @Aggregate
                        @Snapshotting(afterEvents = 50)
                        class MyAggregate

                        @Configuration
                        class MyConfig {
                        }
                        """
                )
        );
    }

    @Test
    void migratesLoadTimeBeanToAfterSourcingTime() {
        rewriteRun(
                kotlin(
                        """
                        package com.example

                        import org.axonframework.eventsourcing.AggregateLoadTimeSnapshotTriggerDefinition
                        import org.axonframework.eventsourcing.SnapshotTriggerDefinition
                        import org.axonframework.eventsourcing.Snapshotter
                        import org.axonframework.spring.stereotype.Aggregate
                        import org.springframework.context.annotation.Bean
                        import org.springframework.context.annotation.Configuration

                        @Aggregate(snapshotTriggerDefinition = "timeTrigger")
                        class Order

                        @Configuration
                        class OrderConfig {
                            @Bean
                            fun timeTrigger(snapshotter: Snapshotter): SnapshotTriggerDefinition {
                                return AggregateLoadTimeSnapshotTriggerDefinition(snapshotter, 5000)
                            }
                        }
                        """,
                        """
                        package com.example

                        import org.axonframework.eventsourcing.annotation.Snapshotting
                        import org.axonframework.spring.stereotype.Aggregate
                        import org.springframework.context.annotation.Configuration

                        @Aggregate
                        @Snapshotting(afterSourcingTime = "PT5S")
                        class Order

                        @Configuration
                        class OrderConfig {
                        }
                        """
                )
        );
    }

    @Test
    void addsTodoForCustomImplementation() {
        rewriteRun(
                kotlin(
                        """
                        package com.example

                        import org.axonframework.eventsourcing.SnapshotTriggerDefinition
                        import org.axonframework.spring.stereotype.Aggregate
                        import org.springframework.context.annotation.Bean
                        import org.springframework.context.annotation.Configuration

                        @Aggregate(snapshotTriggerDefinition = "customTrigger")
                        class MyAggregate

                        @Configuration
                        class MyConfig {
                            @Bean
                            fun customTrigger(): SnapshotTriggerDefinition {
                                return CustomSnapshotTriggerDefinition()
                            }
                        }
                        """,
                        """
                        package com.example

                        import org.axonframework.eventsourcing.SnapshotTriggerDefinition
                        import org.axonframework.spring.stereotype.Aggregate
                        import org.springframework.context.annotation.Bean
                        import org.springframework.context.annotation.Configuration

                        // TODO(axon4to5): Custom SnapshotTriggerDefinition "customTrigger" cannot be migrated automatically.
                        @Aggregate
                        class MyAggregate

                        @Configuration
                        class MyConfig {
                            // TODO(axon4to5): Custom SnapshotTriggerDefinition bean "customTrigger" — remove this bean manually.
                            @Bean
                            fun customTrigger(): SnapshotTriggerDefinition {
                                return CustomSnapshotTriggerDefinition()
                            }
                        }
                        """
                )
        );
    }
}

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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for {@link MigrateSnapshotTriggerDefinitionToAnnotation}.
 */
class MigrateSnapshotTriggerDefinitionToAnnotationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateSnapshotTriggerDefinitionToAnnotation())
            .parser(JavaParser.fromJavaVersion()
                              .classpath("axon-eventsourcing", "axon-spring"))
            .typeValidationOptions(TypeValidation.none());
    }

    // -------------------------------------------------------------------------
    // EventCountSnapshotTriggerDefinition → @Snapshotting(afterEvents = N)
    // -------------------------------------------------------------------------

    @Nested
    class EventCountSnapshotTriggerDefinitionMigration {

        @Test
        void migratesEventCountBeanAndAggregateInSameFile() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.eventsourcing.EventCountSnapshotTriggerDefinition;
                            import org.axonframework.eventsourcing.SnapshotTriggerDefinition;
                            import org.axonframework.eventsourcing.Snapshotter;
                            import org.axonframework.spring.stereotype.Aggregate;
                            import org.springframework.context.annotation.Bean;
                            import org.springframework.context.annotation.Configuration;

                            @Aggregate(snapshotTriggerDefinition = "myTrigger")
                            class MyAggregate {}

                            @Configuration
                            class MyConfig {
                                @Bean
                                SnapshotTriggerDefinition myTrigger(Snapshotter snapshotter) {
                                    return new EventCountSnapshotTriggerDefinition(snapshotter, 50);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.eventsourcing.annotation.Snapshotting;
                            import org.axonframework.spring.stereotype.Aggregate;
                            import org.springframework.context.annotation.Configuration;

                            @Aggregate
                            @Snapshotting(afterEvents = 50)
                            class MyAggregate {}

                            @Configuration
                            class MyConfig {
                            }
                            """
                    )
            );
        }

        @Test
        void migratesEventCountBeanAndAggregateInSeparateFiles() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.spring.stereotype.Aggregate;

                            @Aggregate(snapshotTriggerDefinition = "dwellingSnapshotTrigger")
                            class Dwelling {}
                            """,
                            """
                            package com.example;

                            import org.axonframework.eventsourcing.annotation.Snapshotting;
                            import org.axonframework.spring.stereotype.Aggregate;

                            @Aggregate
                            @Snapshotting(afterEvents = 5)
                            class Dwelling {}
                            """
                    ),
                    java(
                            """
                            package com.example;

                            import org.axonframework.eventsourcing.EventCountSnapshotTriggerDefinition;
                            import org.axonframework.eventsourcing.SnapshotTriggerDefinition;
                            import org.axonframework.eventsourcing.Snapshotter;
                            import org.springframework.context.annotation.Bean;
                            import org.springframework.context.annotation.Configuration;

                            @Configuration
                            class DomainConfig {
                                @Bean
                                SnapshotTriggerDefinition dwellingSnapshotTrigger(Snapshotter snapshotter) {
                                    return new EventCountSnapshotTriggerDefinition(snapshotter, 5);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.springframework.context.annotation.Configuration;

                            @Configuration
                            class DomainConfig {
                            }
                            """
                    )
            );
        }

        @Test
        void keepsOtherBeansWhenRemovingSnapshotBean() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.eventsourcing.EventCountSnapshotTriggerDefinition;
                            import org.axonframework.eventsourcing.SnapshotTriggerDefinition;
                            import org.axonframework.eventsourcing.Snapshotter;
                            import org.springframework.context.annotation.Bean;
                            import org.springframework.context.annotation.Configuration;

                            @Configuration
                            class MultiConfig {
                                @Bean
                                SnapshotTriggerDefinition myTrigger(Snapshotter snapshotter) {
                                    return new EventCountSnapshotTriggerDefinition(snapshotter, 100);
                                }

                                @Bean
                                String someOtherBean() {
                                    return "hello";
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.springframework.context.annotation.Bean;
                            import org.springframework.context.annotation.Configuration;

                            @Configuration
                            class MultiConfig {

                                @Bean
                                String someOtherBean() {
                                    return "hello";
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void removesOnlySnapshotTriggerDefinitionAttributeWhenOtherAttributesPresent() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.eventsourcing.EventCountSnapshotTriggerDefinition;
                            import org.axonframework.eventsourcing.SnapshotTriggerDefinition;
                            import org.axonframework.eventsourcing.Snapshotter;
                            import org.axonframework.spring.stereotype.Aggregate;
                            import org.springframework.context.annotation.Bean;
                            import org.springframework.context.annotation.Configuration;

                            @Aggregate(snapshotTriggerDefinition = "myTrigger", repository = "myRepo")
                            class MyAggregate {}

                            @Configuration
                            class MyConfig {
                                @Bean
                                SnapshotTriggerDefinition myTrigger(Snapshotter snapshotter) {
                                    return new EventCountSnapshotTriggerDefinition(snapshotter, 20);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.eventsourcing.annotation.Snapshotting;
                            import org.axonframework.spring.stereotype.Aggregate;
                            import org.springframework.context.annotation.Configuration;

                            @Aggregate(repository = "myRepo")
                            @Snapshotting(afterEvents = 20)
                            class MyAggregate {}

                            @Configuration
                            class MyConfig {
                            }
                            """
                    )
            );
        }

        @Test
        void removesAf5SnapshotterImportWhenEventsourcingRecipeRanFirst() {
            // When axon4-to-axon5-eventsourcing.yml runs before this recipe, it renames
            // org.axonframework.eventsourcing.Snapshotter →
            // org.axonframework.eventsourcing.snapshot.api.Snapshotter.
            // The bean method must still be removed and that renamed import must be cleaned up.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.eventsourcing.EventCountSnapshotTriggerDefinition;
                            import org.axonframework.eventsourcing.SnapshotTriggerDefinition;
                            import org.axonframework.eventsourcing.snapshot.api.Snapshotter;
                            import org.axonframework.spring.stereotype.Aggregate;
                            import org.springframework.context.annotation.Bean;
                            import org.springframework.context.annotation.Configuration;

                            @Aggregate(snapshotTriggerDefinition = "myTrigger")
                            class MyAggregate {}

                            @Configuration
                            class MyConfig {
                                @Bean
                                SnapshotTriggerDefinition myTrigger(Snapshotter snapshotter) {
                                    return new EventCountSnapshotTriggerDefinition(snapshotter, 50);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.eventsourcing.annotation.Snapshotting;
                            import org.axonframework.spring.stereotype.Aggregate;
                            import org.springframework.context.annotation.Configuration;

                            @Aggregate
                            @Snapshotting(afterEvents = 50)
                            class MyAggregate {}

                            @Configuration
                            class MyConfig {
                            }
                            """
                    )
            );
        }

        @Test
        void isIdempotent() {
            // Running the recipe a second time must produce no further changes.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.eventsourcing.annotation.Snapshotting;
                            import org.axonframework.spring.stereotype.Aggregate;

                            @Aggregate
                            @Snapshotting(afterEvents = 50)
                            class MyAggregate {}
                            """
                    )
            );
        }
    }

    // -------------------------------------------------------------------------
    // AggregateLoadTimeSnapshotTriggerDefinition → @Snapshotting(afterSourcingTime)
    // -------------------------------------------------------------------------

    @Nested
    class AggregateLoadTimeSnapshotTriggerDefinitionMigration {

        @Test
        void migratesLoadTimeBeanToAfterSourcingTime() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.eventsourcing.AggregateLoadTimeSnapshotTriggerDefinition;
                            import org.axonframework.eventsourcing.SnapshotTriggerDefinition;
                            import org.axonframework.eventsourcing.Snapshotter;
                            import org.axonframework.spring.stereotype.Aggregate;
                            import org.springframework.context.annotation.Bean;
                            import org.springframework.context.annotation.Configuration;

                            @Aggregate(snapshotTriggerDefinition = "timeTrigger")
                            class Order {}

                            @Configuration
                            class OrderConfig {
                                @Bean
                                SnapshotTriggerDefinition timeTrigger(Snapshotter snapshotter) {
                                    return new AggregateLoadTimeSnapshotTriggerDefinition(snapshotter, 5000);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.eventsourcing.annotation.Snapshotting;
                            import org.axonframework.spring.stereotype.Aggregate;
                            import org.springframework.context.annotation.Configuration;

                            @Aggregate
                            @Snapshotting(afterSourcingTime = "PT5S")
                            class Order {}

                            @Configuration
                            class OrderConfig {
                            }
                            """
                    )
            );
        }

        @Test
        void convertsMillisecondsToIsoDuration() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.eventsourcing.AggregateLoadTimeSnapshotTriggerDefinition;
                            import org.axonframework.eventsourcing.SnapshotTriggerDefinition;
                            import org.axonframework.eventsourcing.Snapshotter;
                            import org.axonframework.spring.stereotype.Aggregate;
                            import org.springframework.context.annotation.Bean;
                            import org.springframework.context.annotation.Configuration;

                            @Aggregate(snapshotTriggerDefinition = "timeTrigger")
                            class Order {}

                            @Configuration
                            class OrderConfig {
                                @Bean
                                SnapshotTriggerDefinition timeTrigger(Snapshotter snapshotter) {
                                    return new AggregateLoadTimeSnapshotTriggerDefinition(snapshotter, 90000);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.eventsourcing.annotation.Snapshotting;
                            import org.axonframework.spring.stereotype.Aggregate;
                            import org.springframework.context.annotation.Configuration;

                            @Aggregate
                            @Snapshotting(afterSourcingTime = "PT1M30S")
                            class Order {}

                            @Configuration
                            class OrderConfig {
                            }
                            """
                    )
            );
        }
    }

    // -------------------------------------------------------------------------
    // Custom SnapshotTriggerDefinition → TODO comment
    // -------------------------------------------------------------------------

    @Nested
    class CustomSnapshotTriggerDefinitionMigration {

        @Test
        void addsTodoCommentForCustomImplementation() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.eventsourcing.SnapshotTriggerDefinition;
                            import org.axonframework.spring.stereotype.Aggregate;
                            import org.springframework.context.annotation.Bean;
                            import org.springframework.context.annotation.Configuration;

                            @Aggregate(snapshotTriggerDefinition = "customTrigger")
                            class MyAggregate {}

                            @Configuration
                            class MyConfig {
                                @Bean
                                SnapshotTriggerDefinition customTrigger() {
                                    return new CustomSnapshotTriggerDefinition();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.eventsourcing.SnapshotTriggerDefinition;
                            import org.axonframework.spring.stereotype.Aggregate;
                            import org.springframework.context.annotation.Bean;
                            import org.springframework.context.annotation.Configuration;

                            // TODO(axon4to5): Custom SnapshotTriggerDefinition "customTrigger" cannot be migrated automatically.
                            @Aggregate
                            class MyAggregate {}

                            @Configuration
                            class MyConfig {
                                // TODO(axon4to5): Custom SnapshotTriggerDefinition bean "customTrigger" — remove this bean manually.
                                @Bean
                                SnapshotTriggerDefinition customTrigger() {
                                    return new CustomSnapshotTriggerDefinition();
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void addsTodoCommentWhenBeanNotFoundInAccumulator() {
            // The aggregate references a bean name that no @Bean method provides —
            // the recipe must still remove the attribute and add a TODO comment.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.spring.stereotype.Aggregate;

                            @Aggregate(snapshotTriggerDefinition = "undefinedTrigger")
                            class MyAggregate {}
                            """,
                            """
                            package com.example;

                            import org.axonframework.spring.stereotype.Aggregate;

                            // TODO(axon4to5): Custom SnapshotTriggerDefinition "undefinedTrigger" cannot be migrated automatically.
                            @Aggregate
                            class MyAggregate {}
                            """
                    )
            );
        }
    }

    // -------------------------------------------------------------------------
    // No-op cases
    // -------------------------------------------------------------------------

    @Nested
    class NoOpCases {

        @Test
        void doesNotModifyAggregateWithoutSnapshotTriggerDefinitionAttribute() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.spring.stereotype.Aggregate;

                            @Aggregate
                            class MyAggregate {}
                            """
                    )
            );
        }

        @Test
        void doesNotModifyUnrelatedBeanMethods() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.springframework.context.annotation.Bean;
                            import org.springframework.context.annotation.Configuration;

                            @Configuration
                            class MyConfig {
                                @Bean
                                String myBean() {
                                    return "hello";
                                }
                            }
                            """
                    )
            );
        }
    }
}

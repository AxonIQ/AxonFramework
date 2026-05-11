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

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.kotlin.Assertions.kotlin;

/**
 * Verifies {@link ExploreAxon4Aggregates} discovers AF4 aggregates across Java and Kotlin
 * via {@code @Aggregate}, {@code configureAggregate(...)}, and polymorphism signals;
 * extracts annotation-argument feature flags; and emits one YAML document per aggregate
 * to the expected {@code .axon4-explore/components/aggregate@<fqn>.yaml} path.
 */
class ExploreAxon4AggregatesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        // The recipe generates new YAML source files via ScanningRecipe.generate(), which
        // OpenRewrite's test harness counts as a change cycle. Default tests expect at
        // least one aggregate to be discovered; tests with zero matches override below.
        spec.recipe(new ExploreAxon4Aggregates())
            .typeValidationOptions(TypeValidation.none())
            .expectedCyclesThatMakeChanges(1);
    }

    @Nested
    class JavaDiscovery {

        @Test
        void findsSingleAggregateAndEmitsExpectedYamlAndCsvRow() {
            rewriteRun(
                    spec -> spec.dataTable(AggregateFeaturesTable.Row.class, rows ->
                            Assertions.assertThat(rows)
                                      .extracting(AggregateFeaturesTable.Row::fqcn,
                                                  AggregateFeaturesTable.Row::language,
                                                  AggregateFeaturesTable.Row::configStyle,
                                                  AggregateFeaturesTable.Row::persistence,
                                                  AggregateFeaturesTable.Row::polymorphic)
                                      .containsExactly(Assertions.tuple(
                                              "com.example.OrderAggregate",
                                              "java",
                                              "spring",
                                              "event-sourcing",
                                              false))),
                    java(
                            """
                            package com.example;
                            import org.axonframework.spring.stereotype.Aggregate;
                            import org.axonframework.eventsourcing.EventSourcingHandler;

                            @Aggregate
                            public class OrderAggregate {
                                @EventSourcingHandler
                                void on(Object event) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void extractsSnapshotTriggerArgument() {
            rewriteRun(
                    spec -> spec.dataTable(AggregateFeaturesTable.Row.class, rows ->
                            Assertions.assertThat(rows)
                                      .singleElement()
                                      .satisfies(r -> {
                                          Assertions.assertThat(r.hasSnapshotTrigger()).isTrue();
                                          Assertions.assertThat(r.hasCache()).isFalse();
                                      })),
                    java(
                            """
                            package com.example;
                            import org.axonframework.spring.stereotype.Aggregate;

                            @Aggregate(snapshotTriggerDefinition = "snapshotter")
                            public class Bike {
                            }
                            """
                    )
            );
        }

        @Test
        void extractsCacheArgument() {
            rewriteRun(
                    spec -> spec.dataTable(AggregateFeaturesTable.Row.class, rows ->
                            Assertions.assertThat(rows)
                                      .singleElement()
                                      .satisfies(r -> Assertions.assertThat(r.hasCache()).isTrue())),
                    java(
                            """
                            package com.example;
                            import org.axonframework.spring.stereotype.Aggregate;

                            @Aggregate(cache = "myCache")
                            public class GiftCard {
                            }
                            """
                    )
            );
        }

        @Test
        void plainPojoProducesNoChangesAndNoYaml() {
            // No @Aggregate annotation, no configureAggregate call — recipe should generate
            // nothing. The data table is never materialized in this case (no rows inserted),
            // so the only assertion needed is "zero change cycles".
            rewriteRun(
                    spec -> spec.expectedCyclesThatMakeChanges(0),
                    java(
                            """
                            package com.example;
                            public class PlainPojo {
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class KotlinDiscovery {

        @Test
        void findsKotlinAggregate() {
            rewriteRun(
                    spec -> spec.dataTable(AggregateFeaturesTable.Row.class, rows ->
                            Assertions.assertThat(rows)
                                      .extracting(AggregateFeaturesTable.Row::fqcn,
                                                  AggregateFeaturesTable.Row::language)
                                      .containsExactly(Assertions.tuple(
                                              "com.example.CustomerAggregate",
                                              "kotlin"))),
                    kotlin(
                            """
                            package com.example
                            import org.axonframework.spring.stereotype.Aggregate

                            @Aggregate
                            class CustomerAggregate
                            """
                    )
            );
        }
    }

    @Nested
    class CommandAndEventVocabulary {

        @Test
        void capturesCommandHandlerAndEventSourcingHandlerFirstParamFqns() {
            rewriteRun(
                    spec -> spec.dataTable(AggregateFeaturesTable.Row.class, rows ->
                            Assertions.assertThat(rows)
                                      .singleElement()
                                      .satisfies(r -> {
                                          Assertions.assertThat(r.commands())
                                                    .isEqualTo("com.example.CreateBike,com.example.RentBike");
                                          Assertions.assertThat(r.events())
                                                    .isEqualTo("com.example.BikeCreated,com.example.BikeRented");
                                      })),
                    java(
                            """
                            package com.example;
                            public class CreateBike {}
                            """
                    ),
                    java(
                            """
                            package com.example;
                            public class RentBike {}
                            """
                    ),
                    java(
                            """
                            package com.example;
                            public class BikeCreated {}
                            """
                    ),
                    java(
                            """
                            package com.example;
                            public class BikeRented {}
                            """
                    ),
                    java(
                            """
                            package com.example;
                            import org.axonframework.spring.stereotype.Aggregate;
                            import org.axonframework.commandhandling.CommandHandler;
                            import org.axonframework.eventsourcing.EventSourcingHandler;

                            @Aggregate
                            public class BikeAggregate {
                                @CommandHandler
                                public BikeAggregate(CreateBike cmd) {
                                }
                                @CommandHandler
                                void on(RentBike cmd) {
                                }
                                @EventSourcingHandler
                                void on(BikeCreated event) {
                                }
                                @EventSourcingHandler
                                void on(BikeRented event) {
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void capturesKotlinCommandAndEventFqns() {
            rewriteRun(
                    spec -> spec.dataTable(AggregateFeaturesTable.Row.class, rows ->
                            Assertions.assertThat(rows)
                                      .singleElement()
                                      .satisfies(r -> {
                                          Assertions.assertThat(r.commands()).isEqualTo("com.example.PayCommand");
                                          Assertions.assertThat(r.events()).isEqualTo("com.example.PaidEvent");
                                      })),
                    kotlin(
                            """
                            package com.example
                            class PayCommand
                            """
                    ),
                    kotlin(
                            """
                            package com.example
                            class PaidEvent
                            """
                    ),
                    kotlin(
                            """
                            package com.example
                            import org.axonframework.spring.stereotype.Aggregate
                            import org.axonframework.commandhandling.CommandHandler
                            import org.axonframework.eventsourcing.EventSourcingHandler

                            @Aggregate
                            class PaymentAggregate {
                                @CommandHandler
                                fun on(cmd: PayCommand) {}
                                @EventSourcingHandler
                                fun on(event: PaidEvent) {}
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class ConfigureAggregateDiscovery {

        @Test
        void nonSpringAggregateDiscoveredViaConfigureAggregate() {
            rewriteRun(
                    spec -> spec.dataTable(AggregateFeaturesTable.Row.class, rows ->
                            Assertions.assertThat(rows)
                                      .singleElement()
                                      .satisfies(r -> {
                                          Assertions.assertThat(r.fqcn()).isEqualTo("com.example.NonSpringAgg");
                                          Assertions.assertThat(r.configStyle()).isEqualTo("non-spring");
                                          Assertions.assertThat(r.discovery()).contains("configureAggregate");
                                      })),
                    java(
                            """
                            package com.example;
                            public class NonSpringAgg {
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;
                            class WiringConfig {
                                void configure() {
                                    Object cfg = null;
                                    configureAggregate(NonSpringAgg.class);
                                }
                                static <T> Object configureAggregate(Class<T> type) { return null; }
                            }
                            """
                    )
            );
        }
    }

    @Nested
    class Polymorphism {

        @Test
        void signal1ParentChildBothMarkedViaExtendsChain() {
            rewriteRun(
                    spec -> spec.dataTable(AggregateFeaturesTable.Row.class, rows ->
                            Assertions.assertThat(rows)
                                      .extracting(AggregateFeaturesTable.Row::fqcn,
                                                  AggregateFeaturesTable.Row::polymorphic,
                                                  AggregateFeaturesTable.Row::polymorphicRole,
                                                  AggregateFeaturesTable.Row::polymorphicParent)
                                      .containsExactlyInAnyOrder(
                                              Assertions.tuple("com.example.Vehicle", true, "parent", ""),
                                              Assertions.tuple("com.example.Car", true, "child", "com.example.Vehicle"))),
                    java(
                            """
                            package com.example;
                            import org.axonframework.spring.stereotype.Aggregate;

                            @Aggregate
                            public class Vehicle {
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;
                            import org.axonframework.spring.stereotype.Aggregate;

                            @Aggregate
                            public class Car extends Vehicle {
                            }
                            """
                    )
            );
        }

        @Test
        void signal2ParentChildMarkedViaWithSubtypes() {
            rewriteRun(
                    spec -> spec.dataTable(AggregateFeaturesTable.Row.class, rows ->
                            Assertions.assertThat(rows)
                                      .extracting(AggregateFeaturesTable.Row::fqcn,
                                                  AggregateFeaturesTable.Row::polymorphic,
                                                  AggregateFeaturesTable.Row::polymorphicRole)
                                      .containsExactlyInAnyOrder(
                                              Assertions.tuple("com.example.Animal", true, "parent"),
                                              Assertions.tuple("com.example.Dog", true, "child"))),
                    java(
                            """
                            package com.example;
                            public class Animal {
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;
                            public class Dog extends Animal {
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;
                            class Wiring {
                                static <T> Wiring configureAggregate(Class<T> type) { return new Wiring(); }
                                Wiring withSubtypes(Class<?>... subs) { return this; }
                                void configure() {
                                    configureAggregate(Animal.class).withSubtypes(Dog.class);
                                }
                            }
                            """
                    )
            );
        }
    }
}

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
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies {@link MigrateDeadlineManagerBuilders}: the builders of the Axon Framework 4 deadline managers lose the
 * parameters the legacy module dropped, and get a {@code TODO(axon4to5)} marker for what cannot be inferred.
 */
class MigrateDeadlineManagerBuildersTest implements RewriteTest {

    private static final String UNIT_OF_WORK_FACTORY_TODO =
            "// TODO(axon4to5): add unitOfWorkFactory(..) to this deadline manager builder, for example"
                    + " configuration.getComponent(UnitOfWorkFactory.class) or the UnitOfWorkFactory Spring bean."
                    + " The legacy deadline managers require it in place of transactionManager(..): a fired deadline"
                    + " runs in a unit of work, and transaction, of that factory.";
    private static final String SERIALIZER_TODO =
            "// TODO(axon4to5): replace serializer(..) with converter(..), passing the Converter matching the Axon"
                    + " Framework 4 serializer, so stored deadlines stay readable: general serializer ->"
                    + " GeneralConverter, message serializer -> MessageConverter, event serializer -> EventConverter,"
                    + " a JacksonSerializer with a customized ObjectMapper -> a JacksonConverter configured the same"
                    + " way, XStreamSerializer -> XStreamConverter allowing your payload and scope types.";
    private static final String QUARTZ_CONVERTER_TODO =
            "// TODO(axon4to5): add converter(..) to this deadline manager builder. Axon Framework 4 defaulted the"
                    + " Quartz deadline manager to an XStreamSerializer; pass an XStreamConverter allowing your"
                    + " payload and scope types to read the jobs it stored.";

    @Override
    public void defaults(RecipeSpec spec) {
        // Quartz, JobRunr and db-scheduler are not on the test classpath, so calls after one taking their types do
        // not resolve, as in an application that misses one of them.
        spec.recipe(new MigrateDeadlineManagerBuilders())
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class RemovedBuilderParameters {

        @Test
        void removesSpanFactoryAndTransactionManagerFromSimpleDeadlineManagerBuilder() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.common.transaction.TransactionManager;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.DeadlineManagerSpanFactory;
                            import org.axonframework.deadline.SimpleDeadlineManager;
                            import org.axonframework.messaging.ScopeAwareProvider;

                            import java.util.concurrent.ScheduledExecutorService;

                            class DeadlineConfig {
                                DeadlineManager deadlineManager(ScopeAwareProvider provider,
                                                                ScheduledExecutorService executor,
                                                                TransactionManager transactionManager,
                                                                DeadlineManagerSpanFactory spanFactory) {
                                    return SimpleDeadlineManager.builder()
                                                                .scopeAwareProvider(provider)
                                                                .transactionManager(transactionManager)
                                                                .scheduledExecutorService(executor)
                                                                .spanFactory(spanFactory)
                                                                .build();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.common.transaction.TransactionManager;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.DeadlineManagerSpanFactory;
                            import org.axonframework.deadline.SimpleDeadlineManager;
                            import org.axonframework.messaging.ScopeAwareProvider;

                            import java.util.concurrent.ScheduledExecutorService;

                            class DeadlineConfig {
                                DeadlineManager deadlineManager(ScopeAwareProvider provider,
                                                                ScheduledExecutorService executor,
                                                                TransactionManager transactionManager,
                                                                DeadlineManagerSpanFactory spanFactory) {
                                    %s
                                    return SimpleDeadlineManager.builder()
                                                                .scopeAwareProvider(provider)
                                                                .scheduledExecutorService(executor)
                                                                .build();
                                }
                            }
                            """.formatted(UNIT_OF_WORK_FACTORY_TODO)
                    )
            );
        }

        @Test
        void removesBothSpanFactoryOverloadsFromQuartzDeadlineManagerBuilder() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.common.transaction.TransactionManager;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.quartz.QuartzDeadlineManager;
                            import org.axonframework.messaging.ScopeAwareProvider;
                            import org.axonframework.serialization.json.JacksonSerializer;
                            import org.axonframework.tracing.SpanFactory;
                            import org.quartz.Scheduler;

                            class DeadlineConfig {
                                DeadlineManager deadlineManager(Scheduler scheduler,
                                                                ScopeAwareProvider provider,
                                                                TransactionManager transactionManager,
                                                                SpanFactory spanFactory) {
                                    return QuartzDeadlineManager.builder()
                                                                .scheduler(scheduler)
                                                                .scopeAwareProvider(provider)
                                                                .transactionManager(transactionManager)
                                                                .serializer(JacksonSerializer.defaultSerializer())
                                                                .spanFactory(spanFactory)
                                                                .refireImmediatelyPolicy(error -> false)
                                                                .build();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.common.transaction.TransactionManager;
                            import org.axonframework.conversion.jackson.JacksonConverter;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.quartz.QuartzDeadlineManager;
                            import org.axonframework.messaging.ScopeAwareProvider;
                            import org.axonframework.tracing.SpanFactory;
                            import org.quartz.Scheduler;

                            class DeadlineConfig {
                                DeadlineManager deadlineManager(Scheduler scheduler,
                                                                ScopeAwareProvider provider,
                                                                TransactionManager transactionManager,
                                                                SpanFactory spanFactory) {
                                    %s
                                    return QuartzDeadlineManager.builder()
                                                                .scheduler(scheduler)
                                                                .scopeAwareProvider(provider)
                                                                .converter(new JacksonConverter())
                                                                .refireImmediatelyPolicy(error -> false)
                                                                .build();
                                }
                            }
                            """.formatted(UNIT_OF_WORK_FACTORY_TODO)
                    )
            );
        }

        @Test
        void removesTransactionManagerFromJobRunrDeadlineManagerBuilderAssignedToField() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.common.transaction.TransactionManager;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.jobrunr.JobRunrDeadlineManager;
                            import org.axonframework.messaging.ScopeAwareProvider;
                            import org.axonframework.serialization.Serializer;
                            import org.jobrunr.scheduling.JobScheduler;

                            class DeadlineConfig {
                                private final DeadlineManager deadlineManager;

                                DeadlineConfig(JobScheduler jobScheduler,
                                               ScopeAwareProvider provider,
                                               Serializer serializer,
                                               TransactionManager transactionManager) {
                                    deadlineManager = JobRunrDeadlineManager.builder()
                                                                            .jobScheduler(jobScheduler)
                                                                            .scopeAwareProvider(provider)
                                                                            .serializer(serializer)
                                                                            .transactionManager(transactionManager)
                                                                            .build();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.common.transaction.TransactionManager;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.jobrunr.JobRunrDeadlineManager;
                            import org.axonframework.messaging.ScopeAwareProvider;
                            import org.axonframework.serialization.Serializer;
                            import org.jobrunr.scheduling.JobScheduler;

                            class DeadlineConfig {
                                private final DeadlineManager deadlineManager;

                                DeadlineConfig(JobScheduler jobScheduler,
                                               ScopeAwareProvider provider,
                                               Serializer serializer,
                                               TransactionManager transactionManager) {
                                    %s
                                    %s
                                    deadlineManager = JobRunrDeadlineManager.builder()
                                                                            .jobScheduler(jobScheduler)
                                                                            .scopeAwareProvider(provider)
                                                                            .serializer(serializer)
                                                                            .build();
                                }
                            }
                            """.formatted(UNIT_OF_WORK_FACTORY_TODO, SERIALIZER_TODO)
                    )
            );
        }

        @Test
        void removesTransactionManagerFromDbSchedulerDeadlineManagerBuilderKeepingItsOptions() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import com.github.kagkarlsson.scheduler.Scheduler;
                            import org.axonframework.common.transaction.TransactionManager;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.dbscheduler.DbSchedulerDeadlineManager;
                            import org.axonframework.messaging.ScopeAwareProvider;
                            import org.axonframework.serialization.json.JacksonSerializer;

                            class DeadlineConfig {
                                DeadlineManager deadlineManager(Scheduler scheduler,
                                                                ScopeAwareProvider provider,
                                                                TransactionManager transactionManager) {
                                    return DbSchedulerDeadlineManager.builder()
                                                                     .scheduler(scheduler)
                                                                     .scopeAwareProvider(provider)
                                                                     .serializer(JacksonSerializer.builder().build())
                                                                     .transactionManager(transactionManager)
                                                                     .useBinaryPojo(false)
                                                                     .startScheduler(false)
                                                                     .stopScheduler(false)
                                                                     .build();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import com.github.kagkarlsson.scheduler.Scheduler;
                            import org.axonframework.common.transaction.TransactionManager;
                            import org.axonframework.conversion.jackson.JacksonConverter;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.dbscheduler.DbSchedulerDeadlineManager;
                            import org.axonframework.messaging.ScopeAwareProvider;

                            class DeadlineConfig {
                                DeadlineManager deadlineManager(Scheduler scheduler,
                                                                ScopeAwareProvider provider,
                                                                TransactionManager transactionManager) {
                                    %s
                                    return DbSchedulerDeadlineManager.builder()
                                                                     .scheduler(scheduler)
                                                                     .scopeAwareProvider(provider)
                                                                     .converter(new JacksonConverter())
                                                                     .useBinaryPojo(false)
                                                                     .startScheduler(false)
                                                                     .stopScheduler(false)
                                                                     .build();
                                }
                            }
                            """.formatted(UNIT_OF_WORK_FACTORY_TODO)
                    )
            );
        }

        @Test
        void removesCallsOnBuilderHeldInVariable() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.common.transaction.TransactionManager;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.SimpleDeadlineManager;
                            import org.axonframework.messaging.ScopeAwareProvider;

                            class DeadlineConfig {
                                DeadlineManager deadlineManager(ScopeAwareProvider provider,
                                                                TransactionManager transactionManager) {
                                    SimpleDeadlineManager.Builder builder = SimpleDeadlineManager.builder();
                                    builder.scopeAwareProvider(provider);
                                    builder.transactionManager(transactionManager);
                                    return builder.build();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.common.transaction.TransactionManager;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.SimpleDeadlineManager;
                            import org.axonframework.messaging.ScopeAwareProvider;

                            class DeadlineConfig {
                                DeadlineManager deadlineManager(ScopeAwareProvider provider,
                                                                TransactionManager transactionManager) {
                                    %s
                                    SimpleDeadlineManager.Builder builder = SimpleDeadlineManager.builder();
                                    builder.scopeAwareProvider(provider);
                                    return builder.build();
                                }
                            }
                            """.formatted(UNIT_OF_WORK_FACTORY_TODO)
                    )
            );
        }

        @Test
        void marksBuilderInLambdaOnItsEnclosingStatement() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.config.Configurer;
                            import org.axonframework.deadline.SimpleDeadlineManager;
                            import org.axonframework.messaging.ScopeAwareProvider;

                            class DeadlineConfig {
                                void configure(Configurer configurer) {
                                    configurer.registerComponent(
                                            SimpleDeadlineManager.class,
                                            config -> SimpleDeadlineManager.builder()
                                                                           .scopeAwareProvider(config.getComponent(ScopeAwareProvider.class))
                                                                           .transactionManager(config.getComponent(org.axonframework.common.transaction.TransactionManager.class))
                                                                           .build()
                                    );
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.config.Configurer;
                            import org.axonframework.deadline.SimpleDeadlineManager;
                            import org.axonframework.messaging.ScopeAwareProvider;

                            class DeadlineConfig {
                                void configure(Configurer configurer) {
                                    %s
                                    configurer.registerComponent(
                                            SimpleDeadlineManager.class,
                                            config -> SimpleDeadlineManager.builder()
                                                                           .scopeAwareProvider(config.getComponent(ScopeAwareProvider.class))
                                                                           .build()
                                    );
                                }
                            }
                            """.formatted(UNIT_OF_WORK_FACTORY_TODO)
                    )
            );
        }
    }

    @Nested
    class SerializerMigration {

        @Test
        void marksSerializerItCannotMapAndKeepsTheCall() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import com.thoughtworks.xstream.XStream;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.quartz.QuartzDeadlineManager;
                            import org.axonframework.serialization.xml.XStreamSerializer;
                            import org.quartz.Scheduler;

                            class DeadlineConfig {
                                DeadlineManager deadlineManager(Scheduler scheduler, XStream xStream) {
                                    return QuartzDeadlineManager.builder()
                                                                .scheduler(scheduler)
                                                                .serializer(XStreamSerializer.builder().xStream(xStream).build())
                                                                .build();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import com.thoughtworks.xstream.XStream;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.quartz.QuartzDeadlineManager;
                            import org.axonframework.serialization.xml.XStreamSerializer;
                            import org.quartz.Scheduler;

                            class DeadlineConfig {
                                DeadlineManager deadlineManager(Scheduler scheduler, XStream xStream) {
                                    %s
                                    %s
                                    return QuartzDeadlineManager.builder()
                                                                .scheduler(scheduler)
                                                                .serializer(XStreamSerializer.builder().xStream(xStream).build())
                                                                .build();
                                }
                            }
                            """.formatted(UNIT_OF_WORK_FACTORY_TODO, SERIALIZER_TODO)
                    )
            );
        }

        @Test
        void marksJacksonSerializerWithCustomizedObjectMapper() {
            // Axon Framework 4's JacksonSerializer takes a Jackson 2 ObjectMapper, and registers modules on it, so
            // there is no JacksonConverter constructor taking it as is.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import com.fasterxml.jackson.databind.ObjectMapper;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.jobrunr.JobRunrDeadlineManager;
                            import org.axonframework.serialization.json.JacksonSerializer;
                            import org.jobrunr.scheduling.JobScheduler;

                            class DeadlineConfig {
                                DeadlineManager deadlineManager(JobScheduler jobScheduler, ObjectMapper objectMapper) {
                                    return JobRunrDeadlineManager.builder()
                                                                 .jobScheduler(jobScheduler)
                                                                 .serializer(JacksonSerializer.builder().objectMapper(objectMapper).build())
                                                                 .build();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import com.fasterxml.jackson.databind.ObjectMapper;
                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.jobrunr.JobRunrDeadlineManager;
                            import org.axonframework.serialization.json.JacksonSerializer;
                            import org.jobrunr.scheduling.JobScheduler;

                            class DeadlineConfig {
                                DeadlineManager deadlineManager(JobScheduler jobScheduler, ObjectMapper objectMapper) {
                                    %s
                                    %s
                                    return JobRunrDeadlineManager.builder()
                                                                 .jobScheduler(jobScheduler)
                                                                 .serializer(JacksonSerializer.builder().objectMapper(objectMapper).build())
                                                                 .build();
                                }
                            }
                            """.formatted(UNIT_OF_WORK_FACTORY_TODO, SERIALIZER_TODO)
                    )
            );
        }

        @Test
        void marksSerializerCallOnBuilderHeldInVariable() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.jobrunr.JobRunrDeadlineManager;
                            import org.axonframework.serialization.Serializer;

                            class DeadlineConfig {
                                DeadlineManager deadlineManager(JobRunrDeadlineManager.Builder builder, Serializer serializer) {
                                    builder.serializer(serializer);
                                    return builder.build();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.jobrunr.JobRunrDeadlineManager;
                            import org.axonframework.serialization.Serializer;

                            class DeadlineConfig {
                                DeadlineManager deadlineManager(JobRunrDeadlineManager.Builder builder, Serializer serializer) {
                                    %s
                                    builder.serializer(serializer);
                                    return builder.build();
                                }
                            }
                            """.formatted(SERIALIZER_TODO)
                    )
            );
        }

        @Test
        void marksQuartzBuilderThatReliedOnTheXStreamDefault() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.quartz.QuartzDeadlineManager;
                            import org.quartz.Scheduler;

                            class DeadlineConfig {
                                DeadlineManager deadlineManager(Scheduler scheduler) {
                                    return QuartzDeadlineManager.builder().scheduler(scheduler).build();
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.quartz.QuartzDeadlineManager;
                            import org.quartz.Scheduler;

                            class DeadlineConfig {
                                DeadlineManager deadlineManager(Scheduler scheduler) {
                                    %s
                                    %s
                                    return QuartzDeadlineManager.builder().scheduler(scheduler).build();
                                }
                            }
                            """.formatted(UNIT_OF_WORK_FACTORY_TODO, QUARTZ_CONVERTER_TODO)
                    )
            );
        }
    }

    @Nested
    class UnrelatedCode {

        @Test
        void leavesSagaStoreAndEventProcessorBuildersWithTheSameMethodNamesAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.common.transaction.TransactionManager;
                            import org.axonframework.eventhandling.TrackingEventProcessor;
                            import org.axonframework.modelling.saga.repository.jpa.JpaSagaStore;
                            import org.axonframework.serialization.json.JacksonSerializer;

                            class SagaConfig {
                                JpaSagaStore sagaStore() {
                                    return JpaSagaStore.builder()
                                                       .serializer(JacksonSerializer.defaultSerializer())
                                                       .build();
                                }

                                TrackingEventProcessor.Builder processor(TransactionManager transactionManager) {
                                    return TrackingEventProcessor.builder().transactionManager(transactionManager);
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesUserClassNamedLikeADeadlineManagerAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            class SimpleDeadlineManager {
                                static Builder builder() {
                                    return new Builder();
                                }

                                static class Builder {
                                    Builder transactionManager(Object transactionManager) {
                                        return this;
                                    }
                                }
                            }
                            """
                    ),
                    java(
                            """
                            package com.example;

                            class Scheduling {
                                Object deadlines(Object transactionManager) {
                                    return SimpleDeadlineManager.builder().transactionManager(transactionManager);
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesAlreadyMigratedBuilderAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.deadline.DeadlineManager;
                            import org.axonframework.deadline.quartz.QuartzDeadlineManager;
                            import org.quartz.Scheduler;

                            class DeadlineConfig {
                                DeadlineManager deadlineManager(Scheduler scheduler, Object factory, Object converter) {
                                    return QuartzDeadlineManager.builder()
                                                                .scheduler(scheduler)
                                                                .unitOfWorkFactory(factory)
                                                                .converter(converter)
                                                                .build();
                                }
                            }
                            """
                    )
            );
        }
    }
}

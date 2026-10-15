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

import org.junit.jupiter.api.*;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpecs;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies {@link CommentOutSerializerConfiguration}: Axon Framework 4 serializer configuration is commented out under
 * a {@code TODO(axon4to5)} marker, together with the imports only it used.
 */
class CommentOutSerializerConfigurationTest implements RewriteTest {

    private static final String COMMENTED_OUT_TODO =
            "// TODO(axon4to5): Axon Framework 5 uses a Converter instead of a Serializer. Define a Converter, for"
                    + " example a JacksonConverter bean, with the settings of the serializer configuration commented"
                    + " out below, such as ObjectMapper visibility or default typing. Check that it reads the sagas,"
                    + " tokens and deadlines Axon Framework 4 stored.";
    private static final String SERIALIZER_TODO =
            "// TODO(axon4to5): Axon Framework 5 uses a Converter instead of a Serializer. Compare this serializer's"
                    + " configuration, such as ObjectMapper visibility or default typing, with your Converter"
                    + " configuration: sagas, tokens and deadlines stored by Axon Framework 4 are read with it.";

    @Override
    public void defaults(RecipeSpec spec) {
        // XStream, Quartz and JobRunr are not on the test classpath, so calls taking their types do not resolve.
        spec.recipe(new CommentOutSerializerConfiguration())
            .typeValidationOptions(TypeValidation.none());
    }

    /**
     * Declares a Spring annotation, which is not on the test classpath, so it resolves and its import is removed with
     * the code using it.
     */
    private static SourceSpecs springAnnotation(String name) {
        return java(
                """
                package org.springframework.context.annotation;

                public @interface %s {
                }
                """.formatted(name)
        );
    }

    @Nested
    class SerializerMethods {

        @Test
        void commentsOutJacksonSerializerBeanAndRemovesTheImportsOnlyItUsed() {
            rewriteRun(
                    springAnnotation("Bean"),
                    springAnnotation("Primary"),
                    java(
                            """
                            package com.example;

                            import com.fasterxml.jackson.annotation.JsonAutoDetect;
                            import com.fasterxml.jackson.annotation.PropertyAccessor;
                            import org.axonframework.serialization.Serializer;
                            import org.axonframework.serialization.json.JacksonSerializer;
                            import org.springframework.context.annotation.Bean;
                            import org.springframework.context.annotation.Primary;

                            class SerializerConfig {

                                @Bean
                                @Primary
                                public Serializer buildSerializer() {
                                    var jacksonSerializer = JacksonSerializer.builder().defaultTyping().build();
                                    var objectMapper = jacksonSerializer.getObjectMapper();
                                    objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
                                    return jacksonSerializer;
                                }
                            }
                            """,
                            """
                            package com.example;

                            class SerializerConfig {

                                %s
                                // @Bean
                                // @Primary
                                // public Serializer buildSerializer() {
                                //     var jacksonSerializer = JacksonSerializer.builder().defaultTyping().build();
                                //     var objectMapper = jacksonSerializer.getObjectMapper();
                                //     objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
                                //     return jacksonSerializer;
                                // }
                            }
                            """.formatted(COMMENTED_OUT_TODO)
                    )
            );
        }

        @Test
        void commentsOutMethodBuildingSerializerWithoutBeanAnnotation() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.serialization.Serializer;
                            import org.axonframework.serialization.xml.XStreamSerializer;

                            class SerializerConfig {

                                static Serializer serializer(boolean secure) {
                                    if (secure) {
                                        return XStreamSerializer.builder().build();
                                    }

                                    return XStreamSerializer.defaultSerializer();
                                }

                                static String name() {
                                    return "xstream";
                                }
                            }
                            """,
                            """
                            package com.example;

                            class SerializerConfig {

                                %s
                                // static Serializer serializer(boolean secure) {
                                //     if (secure) {
                                //         return XStreamSerializer.builder().build();
                                //     }
                                //
                                //     return XStreamSerializer.defaultSerializer();
                                // }

                                static String name() {
                                    return "xstream";
                                }
                            }
                            """.formatted(COMMENTED_OUT_TODO)
                    )
            );
        }

        @Test
        void commentsOutMethodReturningConfigurationThatBuildsSerializer() {
            // Commenting out the return statement alone leaves the method without a result.
            rewriteRun(
                    springAnnotation("Bean"),
                    java(
                            """
                            package com.example;

                            import org.axonframework.config.ConfigurerModule;
                            import org.axonframework.serialization.json.JacksonSerializer;
                            import org.springframework.context.annotation.Bean;

                            class SerializerConfig {

                                @Bean
                                ConfigurerModule serializerModule() {
                                    return configurer -> configurer.configureSerializer(
                                            configuration -> JacksonSerializer.defaultSerializer()
                                    );
                                }
                            }
                            """,
                            """
                            package com.example;

                            class SerializerConfig {

                                %s
                                // @Bean
                                // ConfigurerModule serializerModule() {
                                //     return configurer -> configurer.configureSerializer(
                                //             configuration -> JacksonSerializer.defaultSerializer()
                                //     );
                                // }
                            }
                            """.formatted(COMMENTED_OUT_TODO)
                    )
            );
        }

        @Test
        void keepsImportOfSerializerTypeStillUsedElsewhere() {
            rewriteRun(
                    springAnnotation("Bean"),
                    java(
                            """
                            package com.example;

                            import org.axonframework.serialization.SerializedObject;
                            import org.axonframework.serialization.Serializer;
                            import org.axonframework.serialization.json.JacksonSerializer;
                            import org.springframework.context.annotation.Bean;

                            class SerializerConfig {

                                @Bean
                                Serializer serializer() {
                                    return JacksonSerializer.defaultSerializer();
                                }

                                SerializedObject<byte[]> write(Serializer serializer, Object payload) {
                                    return serializer.serialize(payload, byte[].class);
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.serialization.SerializedObject;
                            import org.axonframework.serialization.Serializer;

                            class SerializerConfig {

                                %s
                                // @Bean
                                // Serializer serializer() {
                                //     return JacksonSerializer.defaultSerializer();
                                // }

                                SerializedObject<byte[]> write(Serializer serializer, Object payload) {
                                    return serializer.serialize(payload, byte[].class);
                                }
                            }
                            """.formatted(COMMENTED_OUT_TODO)
                    )
            );
        }
    }

    @Nested
    class SerializerStatements {

        @Test
        void commentsOutConfigurerCallBuildingSerializer() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import com.thoughtworks.xstream.XStream;
                            import org.axonframework.config.Configurer;
                            import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
                            import org.axonframework.serialization.xml.XStreamSerializer;

                            class AxonConfig {

                                void configure(Configurer configurer, XStream xStream) {
                                    configurer.configureSerializer(
                                            configuration -> XStreamSerializer.builder().xStream(xStream).build()
                                    );
                                    configurer.configureEmbeddedEventStore(configuration -> new InMemoryEventStorageEngine());
                                }
                            }
                            """,
                            """
                            package com.example;

                            import com.thoughtworks.xstream.XStream;
                            import org.axonframework.config.Configurer;
                            import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;

                            class AxonConfig {

                                void configure(Configurer configurer, XStream xStream) {
                                    %s
                                    // configurer.configureSerializer(
                                    //         configuration -> XStreamSerializer.builder().xStream(xStream).build()
                                    // );
                                    configurer.configureEmbeddedEventStore(configuration -> new InMemoryEventStorageEngine());
                                }
                            }
                            """.formatted(COMMENTED_OUT_TODO)
                    )
            );
        }

        @Test
        void commentsOutConfigurerCallWhoseLambdaReturnsSerializer() {
            // Commenting out the return statement alone leaves the lambda without a result.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import com.fasterxml.jackson.annotation.JsonAutoDetect;
                            import com.fasterxml.jackson.annotation.PropertyAccessor;
                            import com.fasterxml.jackson.databind.ObjectMapper;
                            import org.axonframework.config.Configurer;
                            import org.axonframework.serialization.json.JacksonSerializer;

                            class AxonConfig {

                                void configure(Configurer configurer) {
                                    configurer.configureSerializer(configuration -> {
                                        ObjectMapper objectMapper = new ObjectMapper();
                                        objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
                                        return JacksonSerializer.builder().objectMapper(objectMapper).build();
                                    });
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.config.Configurer;

                            class AxonConfig {

                                void configure(Configurer configurer) {
                                    %s
                                    // configurer.configureSerializer(configuration -> {
                                    //     ObjectMapper objectMapper = new ObjectMapper();
                                    //     objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
                                    //     return JacksonSerializer.builder().objectMapper(objectMapper).build();
                                    // });
                                }
                            }
                            """.formatted(COMMENTED_OUT_TODO)
                    )
            );
        }

        @Test
        void commentsOutFieldInitializedWithSerializer() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.serialization.JavaSerializer;
                            import org.axonframework.serialization.Serializer;

                            class SnapshotSupport {

                                private final String name = "snapshots";

                                private final Serializer serializer = new JavaSerializer();
                            }
                            """,
                            """
                            package com.example;

                            class SnapshotSupport {

                                private final String name = "snapshots";

                                %s
                                // private final Serializer serializer = new JavaSerializer();
                            }
                            """.formatted(COMMENTED_OUT_TODO)
                    )
            );
        }

        @Test
        void marksEnumConstantsBuildingSerializers() {
            // An enum constant cannot be commented out on its own, so it only gets the marker.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.serialization.JavaSerializer;
                            import org.axonframework.serialization.Serializer;
                            import org.axonframework.serialization.json.JacksonSerializer;

                            enum Format {

                                JSON(JacksonSerializer.defaultSerializer()),
                                JAVA(new JavaSerializer());

                                private final Serializer serializer;

                                Format(Serializer serializer) {
                                    this.serializer = serializer;
                                }
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.serialization.JavaSerializer;
                            import org.axonframework.serialization.Serializer;
                            import org.axonframework.serialization.json.JacksonSerializer;

                            enum Format {

                                %s
                                JSON(JacksonSerializer.defaultSerializer()),
                                JAVA(new JavaSerializer());

                                private final Serializer serializer;

                                Format(Serializer serializer) {
                                    this.serializer = serializer;
                                }
                            }
                            """.formatted(SERIALIZER_TODO)
                    )
            );
        }
    }

    @Nested
    class Unchanged {

        @Test
        void leavesCodeWithoutSerializerConfigurationAlone() {
            // A getter returning a Serializer builds none, and a Serializer parameter is configured elsewhere.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.serialization.Serializer;
                            import org.axonframework.serialization.SerializedObject;

                            class PayloadWriter {

                                private final Serializer serializer;

                                PayloadWriter(Serializer serializer) {
                                    this.serializer = serializer;
                                }

                                Serializer getSerializer() {
                                    return serializer;
                                }

                                SerializedObject<byte[]> write(Object payload) {
                                    return serializer.serialize(payload, byte[].class);
                                }
                            }
                            """
                    )
            );
        }

        @Test
        void leavesCommentedOutSerializerConfigurationAlone() {
            // A second run finds no serializer configuration in the commented-out code.
            rewriteRun(
                    java(
                            """
                            package com.example;

                            class SerializerConfig {

                                %s
                                // @Bean
                                // public Serializer serializer() {
                                //     return JacksonSerializer.builder().build();
                                // }
                            }
                            """.formatted(COMMENTED_OUT_TODO)
                    )
            );
        }

        @Test
        void leavesDeadlineManagerSerializerToTheDeadlineBuilderMarker() {
            // MigrateDeadlineManagerBuilders runs first and marks the serializer(..) call it cannot map.
            rewriteRun(
                    spec -> spec.recipes(new MigrateDeadlineManagerBuilders(),
                                         new CommentOutSerializerConfiguration()),
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
                                    // TODO(axon4to5): add unitOfWorkFactory(..) to this deadline manager builder, for example configuration.getComponent(UnitOfWorkFactory.class) or the UnitOfWorkFactory Spring bean. The legacy deadline managers require it in place of transactionManager(..): a fired deadline runs in a unit of work, and transaction, of that factory.
                                    // TODO(axon4to5): replace serializer(..) with converter(..), passing the Converter matching the Axon Framework 4 serializer, so stored deadlines stay readable: general serializer -> GeneralConverter, message serializer -> MessageConverter, event serializer -> EventConverter, a JacksonSerializer with a customized ObjectMapper -> a JacksonConverter configured the same way, XStreamSerializer -> XStreamConverter allowing your payload and scope types.
                                    return JobRunrDeadlineManager.builder()
                                                                 .jobScheduler(jobScheduler)
                                                                 .serializer(JacksonSerializer.builder().objectMapper(objectMapper).build())
                                                                 .build();
                                }
                            }
                            """
                    )
            );
        }
    }
}

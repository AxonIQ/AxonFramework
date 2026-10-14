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
 * Verifies {@link MarkSerializerConfiguration}: Axon Framework 4 serializer configuration gets a
 * {@code TODO(axon4to5)} marker to compare it with the Axon Framework 5 converter configuration.
 */
class MarkSerializerConfigurationTest implements RewriteTest {

    private static final String SERIALIZER_TODO =
            "// TODO(axon4to5): Axon Framework 5 uses a Converter instead of a Serializer. Compare this serializer's"
                    + " configuration, such as ObjectMapper visibility or default typing, with your Converter"
                    + " configuration: sagas, tokens and deadlines stored by Axon Framework 4 are read with it.";

    @Override
    public void defaults(RecipeSpec spec) {
        // XStream, Quartz and JobRunr are not on the test classpath, so calls taking their types do not resolve.
        spec.recipe(new MarkSerializerConfiguration())
            .typeValidationOptions(TypeValidation.none());
    }

    @Nested
    class SerializerMethods {

        @Test
        void marksJacksonSerializerBeanOnce() {
            rewriteRun(
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

                            import com.fasterxml.jackson.annotation.JsonAutoDetect;
                            import com.fasterxml.jackson.annotation.PropertyAccessor;
                            import org.axonframework.serialization.Serializer;
                            import org.axonframework.serialization.json.JacksonSerializer;
                            import org.springframework.context.annotation.Bean;
                            import org.springframework.context.annotation.Primary;

                            class SerializerConfig {

                                %s
                                @Bean
                                @Primary
                                public Serializer buildSerializer() {
                                    var jacksonSerializer = JacksonSerializer.builder().defaultTyping().build();
                                    var objectMapper = jacksonSerializer.getObjectMapper();
                                    objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
                                    return jacksonSerializer;
                                }
                            }
                            """.formatted(SERIALIZER_TODO)
                    )
            );
        }

        @Test
        void marksMethodBuildingSerializerWithoutBeanAnnotationOnce() {
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
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.serialization.Serializer;
                            import org.axonframework.serialization.xml.XStreamSerializer;

                            class SerializerConfig {

                                %s
                                static Serializer serializer(boolean secure) {
                                    if (secure) {
                                        return XStreamSerializer.builder().build();
                                    }
                                    return XStreamSerializer.defaultSerializer();
                                }
                            }
                            """.formatted(SERIALIZER_TODO)
                    )
            );
        }
    }

    @Nested
    class SerializerStatements {

        @Test
        void marksXStreamSerializerBuilderInConfigurerLambda() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import com.thoughtworks.xstream.XStream;
                            import org.axonframework.config.Configurer;
                            import org.axonframework.serialization.xml.XStreamSerializer;

                            class AxonConfig {

                                void configure(Configurer configurer, XStream xStream) {
                                    configurer.configureSerializer(
                                            configuration -> XStreamSerializer.builder().xStream(xStream).build()
                                    );
                                }
                            }
                            """,
                            """
                            package com.example;

                            import com.thoughtworks.xstream.XStream;
                            import org.axonframework.config.Configurer;
                            import org.axonframework.serialization.xml.XStreamSerializer;

                            class AxonConfig {

                                void configure(Configurer configurer, XStream xStream) {
                                    %s
                                    configurer.configureSerializer(
                                            configuration -> XStreamSerializer.builder().xStream(xStream).build()
                                    );
                                }
                            }
                            """.formatted(SERIALIZER_TODO)
                    )
            );
        }

        @Test
        void marksSerializerConstructorInField() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.serialization.JavaSerializer;
                            import org.axonframework.serialization.Serializer;

                            class SnapshotSupport {

                                private final Serializer serializer = new JavaSerializer();
                            }
                            """,
                            """
                            package com.example;

                            import org.axonframework.serialization.JavaSerializer;
                            import org.axonframework.serialization.Serializer;

                            class SnapshotSupport {

                                %s
                                private final Serializer serializer = new JavaSerializer();
                            }
                            """.formatted(SERIALIZER_TODO)
                    )
            );
        }
    }

    @Nested
    class Unmarked {

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
        void leavesAlreadyMarkedSerializerConfigurationAlone() {
            rewriteRun(
                    java(
                            """
                            package com.example;

                            import org.axonframework.serialization.Serializer;
                            import org.axonframework.serialization.json.JacksonSerializer;
                            import org.springframework.context.annotation.Bean;

                            class SerializerConfig {

                                %1$s
                                private final Serializer fallback = JacksonSerializer.defaultSerializer();

                                %1$s
                                @Bean
                                public Serializer serializer() {
                                    return JacksonSerializer.builder().build();
                                }
                            }
                            """.formatted(SERIALIZER_TODO)
                    )
            );
        }

        @Test
        void leavesDeadlineManagerSerializerToTheDeadlineBuilderMarker() {
            // MigrateDeadlineManagerBuilders runs first and marks the serializer(..) call it cannot map.
            rewriteRun(
                    spec -> spec.recipes(new MigrateDeadlineManagerBuilders(), new MarkSerializerConfiguration()),
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

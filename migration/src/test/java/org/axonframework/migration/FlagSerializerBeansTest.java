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
import org.openrewrite.java.ChangePackage;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for {@link FlagSerializerBeans}, run on their own as {@link CommentOutSerializerConfiguration} already comments
 * out the {@code @Bean} serializer methods of Java sources when both run in the {@code Axon4ToAxon5Conversion}
 * composite.
 */
class FlagSerializerBeansTest implements RewriteTest {

    private static final String TODO =
            "// TODO(axon4to5): Replace this Serializer bean with a Converter. AF5 no longer has a Serializer. See "
                    + "docs/reference-guide/modules/migration/pages/paths/serializers.adoc";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FlagSerializerBeans())
            .parser(JavaParser.fromJavaVersion().classpath("axon-messaging", "axon-spring"))
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void flagsSerializerBeanAfterThePackageRename() {
        rewriteRun(
                spec -> spec.recipes(new ChangePackage("org.axonframework.serialization",
                                                       "org.axonframework.conversion",
                                                       true),
                                     new FlagSerializerBeans()),
                java(
                        """
                        package com.example;

                        import org.axonframework.serialization.Serializer;
                        import org.axonframework.serialization.json.JacksonSerializer;
                        import org.springframework.context.annotation.Bean;
                        import org.springframework.context.annotation.Configuration;

                        @Configuration
                        class MyConfig {
                            @Bean
                            Serializer serializer() {
                                return JacksonSerializer.defaultSerializer();
                            }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.conversion.Serializer;
                        import org.axonframework.conversion.json.JacksonSerializer;
                        import org.springframework.context.annotation.Bean;
                        import org.springframework.context.annotation.Configuration;

                        @Configuration
                        class MyConfig {
                            %s
                            @Bean
                            Serializer serializer() {
                                return JacksonSerializer.defaultSerializer();
                            }
                        }
                        """.formatted(TODO)
                )
        );
    }

    @Test
    void flagsSerializerBeanWithoutAnySnapshotTriggerBean() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.serialization.json.JacksonSerializer;
                        import org.springframework.context.annotation.Bean;

                        class MyConfig {
                            @Bean
                            JacksonSerializer eventSerializer() {
                                return JacksonSerializer.defaultSerializer();
                            }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.serialization.json.JacksonSerializer;
                        import org.springframework.context.annotation.Bean;

                        class MyConfig {
                            %s
                            @Bean
                            JacksonSerializer eventSerializer() {
                                return JacksonSerializer.defaultSerializer();
                            }
                        }
                        """.formatted(TODO)
                )
        );
    }

    @Test
    void doesNotFlagSerializersOfOtherLibraries() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.springframework.context.annotation.Bean;

                        class MyConfig {
                            @Bean
                            com.fasterxml.jackson.databind.JsonSerializer<String> jsonSerializer() {
                                return null;
                            }

                            @Bean
                            SomeKafkaSerializer kafkaSerializer() {
                                return null;
                            }
                        }

                        class SomeKafkaSerializer {}
                        """
                )
        );
    }

    @Test
    void doesNotFlagNonBeanMethodsReturningASerializer() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.serialization.json.JacksonSerializer;

                        class Helper {
                            JacksonSerializer serializer() {
                                return JacksonSerializer.defaultSerializer();
                            }
                        }
                        """
                )
        );
    }
}

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
import org.openrewrite.config.Environment;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpecs;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies the conversion-module migration: the {@code org.axonframework.serialization} package moves to
 * {@code org.axonframework.conversion}, and serializer configuration is commented out before that move.
 */
class Axon4ToAxon5ConversionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                               .scanRuntimeClasspath("org.axonframework.migration")
                               .build()
                               .activateRecipes("org.axonframework.migration.Axon4ToAxon5Conversion"))
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

    @Test
    void commentsOutSerializerBeanWithoutImportingTypesAxonFramework5DoesNotHave() {
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

                            // TODO(axon4to5): Axon Framework 5 uses a Converter instead of a Serializer. Define a Converter, for example a JacksonConverter bean, with the settings of the serializer configuration commented out below, such as ObjectMapper visibility or default typing. Check that it reads the sagas, tokens and deadlines Axon Framework 4 stored.
                            // @Bean
                            // @Primary
                            // public Serializer buildSerializer() {
                            //     var jacksonSerializer = JacksonSerializer.builder().defaultTyping().build();
                            //     var objectMapper = jacksonSerializer.getObjectMapper();
                            //     objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
                            //     return jacksonSerializer;
                            // }
                        }
                        """
                )
        );
    }

    @Test
    void renamesSerializationPackage() {
        rewriteRun(
                java(
                        """
                        package com.example;

                        import org.axonframework.serialization.ContentTypeConverter;

                        class Conversions {

                            String convert(ContentTypeConverter<byte[], String> converter, byte[] bytes) {
                                return converter.convert(bytes);
                            }
                        }
                        """,
                        """
                        package com.example;

                        import org.axonframework.conversion.ContentTypeConverter;

                        class Conversions {

                            String convert(ContentTypeConverter<byte[], String> converter, byte[] bytes) {
                                return converter.convert(bytes);
                            }
                        }
                        """
                )
        );
    }
}

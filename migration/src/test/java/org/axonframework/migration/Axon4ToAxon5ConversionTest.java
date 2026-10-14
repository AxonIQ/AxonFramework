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
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Verifies the conversion-module migration: the {@code org.axonframework.serialization} package moves to
 * {@code org.axonframework.conversion}, and serializer configuration is marked before that move.
 */
class Axon4ToAxon5ConversionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        // Spring is not on the test classpath, so its annotations do not resolve.
        spec.recipe(Environment.builder()
                               .scanRuntimeClasspath("org.axonframework.migration")
                               .build()
                               .activateRecipes("org.axonframework.migration.Axon4ToAxon5Conversion"))
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void marksSerializerBeanAndRenamesSerializationPackage() {
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
                        import org.axonframework.conversion.Serializer;
                        import org.axonframework.conversion.json.JacksonSerializer;
                        import org.springframework.context.annotation.Bean;
                        import org.springframework.context.annotation.Primary;

                        class SerializerConfig {

                            // TODO(axon4to5): Axon Framework 5 uses a Converter instead of a Serializer. Compare this serializer's configuration, such as ObjectMapper visibility or default typing, with your Converter configuration: sagas, tokens and deadlines stored by Axon Framework 4 are read with it.
                            @Bean
                            @Primary
                            public Serializer buildSerializer() {
                                var jacksonSerializer = JacksonSerializer.builder().defaultTyping().build();
                                var objectMapper = jacksonSerializer.getObjectMapper();
                                objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
                                return jacksonSerializer;
                            }
                        }
                        """
                )
        );
    }
}

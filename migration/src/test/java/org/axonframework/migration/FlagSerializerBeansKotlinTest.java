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
 * Kotlin-source tests for {@link FlagSerializerBeans}.
 */
class FlagSerializerBeansKotlinTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FlagSerializerBeans())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void flagsSerializerBean() {
        rewriteRun(
                kotlin(
                        """
                        package com.example

                        import org.axonframework.serialization.Serializer
                        import org.axonframework.serialization.json.JacksonSerializer
                        import org.springframework.context.annotation.Bean
                        import org.springframework.context.annotation.Configuration

                        @Configuration
                        class MyConfig {
                            @Bean
                            fun serializer(): Serializer {
                                return JacksonSerializer.defaultSerializer()
                            }
                        }
                        """,
                        """
                        package com.example

                        import org.axonframework.serialization.Serializer
                        import org.axonframework.serialization.json.JacksonSerializer
                        import org.springframework.context.annotation.Bean
                        import org.springframework.context.annotation.Configuration

                        @Configuration
                        class MyConfig {
                            // TODO(axon4to5): Replace this Serializer bean with a Converter. AF5 no longer has a Serializer. See docs/reference-guide/modules/migration/pages/paths/serializers.adoc
                            @Bean
                            fun serializer(): Serializer {
                                return JacksonSerializer.defaultSerializer()
                            }
                        }
                        """
                )
        );
    }

    @Test
    void doesNotFlagNonSerializerBean() {
        rewriteRun(
                kotlin(
                        """
                        package com.example

                        import org.springframework.context.annotation.Bean
                        import org.springframework.context.annotation.Configuration

                        @Configuration
                        class MyConfig {
                            @Bean
                            fun name(): String {
                                return "name"
                            }
                        }
                        """
                )
        );
    }
}

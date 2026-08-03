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

package org.axonframework.common.nullability;

import org.junit.jupiter.api.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike variant: Kotlin nullability read directly by {@link Nullability}, with kotlin-reflect optional on this module
 * and Kotlin test fixtures compiled here.
 */
class NullabilityTest {

    @Nested
    class JavaDeclarations {

        @Test
        void detectsNullableAnnotation() throws NoSuchMethodException {
            Parameter parameter = JavaHolder.class.getDeclaredMethod("nullableParameter", Object.class)
                                                  .getParameters()[0];

            assertThat(Nullability.forParameter(parameter)).isEqualTo(Nullability.NULLABLE);
        }

        @Test
        void reportsUnknownForAnUnannotatedParameter() throws NoSuchMethodException {
            Parameter parameter = JavaHolder.class.getDeclaredMethod("unannotated", Object.class).getParameters()[0];

            assertThat(Nullability.forParameter(parameter)).isEqualTo(Nullability.UNKNOWN);
        }
    }

    @Nested
    class KotlinDeclarations {

        @Test
        void nullableTypeResolvesToNullable() throws NoSuchMethodException {
            Parameter parameter = KotlinNullabilityFixtures.class.getDeclaredMethod("nullableState", String.class)
                                                                 .getParameters()[0];

            assertThat(Nullability.forParameter(parameter)).isEqualTo(Nullability.NULLABLE);
            assertThat(Nullability.isNullable(parameter)).isTrue();
        }

        @Test
        void nonNullTypeResolvesToNonNull() throws NoSuchMethodException {
            Parameter parameter = KotlinNullabilityFixtures.class.getDeclaredMethod("nonNullState", String.class)
                                                                 .getParameters()[0];

            assertThat(Nullability.forParameter(parameter)).isEqualTo(Nullability.NON_NULL);
            assertThat(Nullability.isNullable(parameter)).isFalse();
        }
    }

    @SuppressWarnings("unused")
    private static class JavaHolder {

        void nullableParameter(@Nullable Object value) {
        }

        void unannotated(Object value) {
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.PARAMETER)
        @interface Nullable {

        }
    }
}

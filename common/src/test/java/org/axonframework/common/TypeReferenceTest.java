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

package org.axonframework.common;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link TypeReference}.
 *
 * @author Mateusz Nowak
 */
class TypeReferenceTest {

    @Nested
    class GetTypeAsClass {

        @Test
        void returnsClassItselfForNonGenericType() {
            // given
            TypeReference<String> testSubject = new TypeReference<>() {
            };

            // when
            Class<String> result = testSubject.getTypeAsClass();

            // then
            assertThat(result).isEqualTo(String.class);
        }

        @Test
        void returnsRawTypeForParameterizedType() {
            // given
            TypeReference<Map<String, Object>> testSubject = new TypeReference<>() {
            };

            // when
            Class<Map<String, Object>> result = testSubject.getTypeAsClass();

            // then
            assertThat(result).isEqualTo(Map.class);
        }

        @Test
        void returnsArrayOfErasedComponentTypeForGenericArrayType() {
            // given
            TypeReference<List<String>[]> testSubject = new TypeReference<>() {
            };

            // when
            Class<List<String>[]> result = testSubject.getTypeAsClass();

            // then
            assertThat(result).isEqualTo(List[].class);
        }

        @Test
        void returnsFirstBoundForBoundedTypeVariable() {
            // given
            TypeReference<CharSequence> testSubject = boundedTypeVariableTypeReference();

            // when
            Class<CharSequence> result = testSubject.getTypeAsClass();

            // then
            assertThat(result).isEqualTo(CharSequence.class);
        }

        @Test
        void returnsObjectForUnboundedTypeVariable() {
            // given
            TypeReference<Object> testSubject = unboundedTypeVariableTypeReference();

            // when
            Class<Object> result = testSubject.getTypeAsClass();

            // then
            assertThat(result).isEqualTo(Object.class);
        }

        private static <X extends CharSequence> TypeReference<X> boundedTypeVariableTypeReference() {
            return new TypeReference<>() {
            };
        }

        private static <X> TypeReference<X> unboundedTypeVariableTypeReference() {
            return new TypeReference<>() {
            };
        }
    }
}

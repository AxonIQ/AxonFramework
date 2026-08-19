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

package org.axonframework.extension.kotlin.common

import org.assertj.core.api.Assertions.assertThat
import org.axonframework.common.property.PropertyAccessStrategy
import org.junit.jupiter.api.Test

class KotlinReflectPropertyAccessStrategyTest {

    @JvmInline
    value class PersonId(val value: Int)

    @Test
    fun `access property from data class`() {
        data class Person(val id: Int, val name: String)

        val property = PropertyAccessStrategy.getProperty(Person::class.java, "id")

        assertThat(property?.getValue<Int>(Person(42, "Douglas"))).isEqualTo(42)
    }

    @Test
    fun `access property from data class with value id`() {
        data class Person(val id: PersonId, val name: String)

        val person = Person(PersonId(42), "Douglas")

        val property = PropertyAccessStrategy.getProperty(Person::class.java, "id")

        assertThat(property?.getValue<PersonId>(person)).isEqualTo(PersonId(42))
    }
}

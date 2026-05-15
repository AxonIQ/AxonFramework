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

import org.axonframework.common.property.Property
import org.axonframework.common.property.PropertyAccessStrategy
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Property access strategy that uses kotlin's [KProperty1] reflection to access properties instead
 * of java's [java.lang.reflect.Field]s.
 * This allows to access properties that are not backed by a field, such as properties of kotlin value classes
 * without additional configuration like `@get:JvmName("getId")`.
 *
 * @author Jan Galinski
 * @since 5.1.0
 */
class KotlinReflectPropertyAccessStrategy : PropertyAccessStrategy() {
    companion object {
        @JvmStatic
        fun <T : Any?> kProperty(targetClass: Class<out T>?, property: String): KProperty1<out T, *>? =
            if (targetClass != null && targetClass.isKotlinClass()) {
                targetClass.kotlin.memberProperties.singleOrNull { it.name == property }
            } else {
                null
            }

        data class KotlinReflectProperty<T : Any>(val kProperty: KProperty1<out T, *>) : Property<T> {

            @Suppress("UNCHECKED_CAST")
            override fun <V : Any> getValue(target: T): V {
                val value = kProperty.call(target)
                val returnClass = kProperty.returnType.classifier as? kotlin.reflect.KClass<*>

                if (value != null && returnClass?.isValue == true && !returnClass.isInstance(value)) {
                    val constructor = returnClass.primaryConstructor
                    if (constructor != null && constructor.parameters.size == 1) {
                        return constructor.call(value) as V
                    }
                }

                return value as V
            }
        }
    }

    // needs increased priority to be able to override the default property access strategy
    override fun getPriority(): Int = 1000

    override fun <T : Any> propertyFor(
        targetClass: Class<out T>,
        property: String
    ): Property<T> = KotlinReflectProperty(kProperty(targetClass, property)!!)

}

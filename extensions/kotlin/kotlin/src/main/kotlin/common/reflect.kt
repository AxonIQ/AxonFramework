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

import kotlin.reflect.KFunction
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.full.instanceParameter

/**
 * Checks if the method is defined top level (static) or if it has a receiver type (is a member or extension).
 * @return true, if the method is not defined inside an enclosing type.
 */
fun KFunction<*>.isTopLevel(): Boolean {
    return this.instanceParameter == null && this.extensionReceiverParameter == null
}

/**
 * Checks if the class is a Kotlin class.
 * @return `true`, if the class is annotated with [Metadata].
 */
fun Class<*>.isKotlinClass(): Boolean = this.isAnnotationPresent(Metadata::class.java) || this in KOTLIN_PRIMITIVE_ARRAYS

private val KOTLIN_PRIMITIVE_ARRAYS = setOf(
    BooleanArray::class.java,
    ByteArray::class.java,
    CharArray::class.java,
    DoubleArray::class.java,
    FloatArray::class.java,
    IntArray::class.java,
    LongArray::class.java,
    ShortArray::class.java,
)

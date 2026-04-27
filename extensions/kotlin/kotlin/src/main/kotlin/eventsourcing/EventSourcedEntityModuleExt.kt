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

package org.axonframework.extension.kotlin.eventsourcing

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule
import kotlin.reflect.KClass

/**
 * Kotlin extensions for [EventSourcedEntityModule], allowing to use the `declarative` and `autodetected` functions
 * without the need to specify the (java) type parameters. Wrapped in data object to avoid name clashes other functions.
 *
 * @author Jan Galinski
 * @since 5.1.0
 */
data object EventSourcedEntityModuleExt {

    /**
     * @see EventSourcedEntityModule.declarative
     */
    inline fun <reified ID : Any, reified E : Any> declarative(): EventSourcedEntityModule.MessagingModelPhase<ID, E> =
        declarative(ID::class, E::class)

    /**
     * @see EventSourcedEntityModule.declarative
     */
    fun <ID : Any, E : Any> declarative(
        idType: KClass<ID>,
        entityType: KClass<E>
    ): EventSourcedEntityModule.MessagingModelPhase<ID, E> =
        EventSourcedEntityModule.declarative(idType.java, entityType.java)

    /**
     * @see EventSourcedEntityModule.autodetected
     */
    inline fun <reified ID : Any, reified E : Any> autodetected(): EventSourcedEntityModule<ID, E> =
        autodetected(ID::class, E::class)

    /**
     * @see EventSourcedEntityModule.autodetected
     */
    fun <ID : Any, E : Any> autodetected(
        idType: KClass<ID>,
        entityType: KClass<E>
    ): EventSourcedEntityModule<ID, E> =
        EventSourcedEntityModule.autodetected(idType.java, entityType.java)

}

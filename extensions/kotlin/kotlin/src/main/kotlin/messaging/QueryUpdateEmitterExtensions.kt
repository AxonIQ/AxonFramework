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

package org.axonframework.extension.kotlin.messaging

import org.axonframework.messaging.queryhandling.QueryUpdateEmitter

/**
 * Reified version of [org.axonframework.messaging.queryhandling.QueryUpdateEmitter.emit] which uses generics
 * to indicate Query type and Update type.
 *
 * Emits given incremental update to subscription queries matching given generic query type and filter.
 *
 * @param update    incremental update
 * @param filter    predicate on query payload used to filter subscription queries
 * @param Q         the type of the query
 * @param U         the type of the update
 * @see org.axonframework.messaging.queryhandling.QueryUpdateEmitter.emit
 * @author Stefan Andjelkovic
 * @since 5.1.0
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified Q : Any, reified U : Any> QueryUpdateEmitter.emit(update: U, noinline filter: (Q) -> Boolean) =
        this.emit(Q::class.java, { q: Q -> filter(q) }, update)

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

import org.axonframework.messaging.queryhandling.gateway.QueryGateway
import org.reactivestreams.Publisher
import java.util.concurrent.CompletableFuture

/**
 * Query Gateway extensions.
 *
 * @author Henrique Sena
 */

/**
 * Reified version of [QueryGateway.query] expecting a single instance result.
 *
 * @param query Query to send
 * @param R the type of result of the query
 * @return [CompletableFuture] wrapping the result of the query
 * @see QueryGateway.query
 * @since 5.1.0
 */
inline fun <reified R : Any> QueryGateway.query(query: Any): CompletableFuture<R> =
    this.query(query, R::class.java)

/**
 * Reified version of [QueryGateway.queryMany] expecting a list result.
 *
 * @param query Query to send
 * @param R the type of each element in the result list
 * @return [CompletableFuture] wrapping the list of results
 * @see QueryGateway.queryMany
 * @since 5.1.0
 */
inline fun <reified R : Any> QueryGateway.queryMany(query: Any): CompletableFuture<List<R>> =
    this.queryMany(query, R::class.java)

/**
 * Reified version of [QueryGateway.subscriptionQuery] returning a [Publisher] of updates.
 *
 * @param query Query to send
 * @param R the type of each update
 * @return [Publisher] of updates
 * @see QueryGateway.subscriptionQuery
 * @since 0.5.0
 */
inline fun <reified R : Any> QueryGateway.subscriptionQuery(query: Any): Publisher<R> =
    this.subscriptionQuery(query, R::class.java)

/**
 * Reified version of [QueryGateway.streamingQuery] returning a [Publisher] of results.
 *
 * @param query Query to send
 * @param R the type of each result element
 * @return [Publisher] of results
 * @see QueryGateway.streamingQuery
 * @since 5.1.0
 */
inline fun <reified R : Any> QueryGateway.streamingQuery(query: Any): Publisher<R> =
    this.streamingQuery(query, R::class.java)

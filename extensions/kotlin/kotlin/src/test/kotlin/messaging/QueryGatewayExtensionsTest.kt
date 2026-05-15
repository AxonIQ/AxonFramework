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

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.axonframework.extension.kotlin.ExampleQuery
import org.axonframework.extension.kotlin.UpdateResponseType
import org.axonframework.messaging.queryhandling.gateway.QueryGateway
import org.reactivestreams.Publisher
import java.util.concurrent.CompletableFuture
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * Tests Query Gateway extensions.
 *
 * @author Stefan Andjelkovic
 * @author Henrique Sena
 */
internal class QueryGatewayExtensionsTest {

    private val exampleQuery = ExampleQuery(2)
    private val instanceReturnValue: CompletableFuture<String> = CompletableFuture.completedFuture("2")
    private val listReturnValue: CompletableFuture<List<String>> = CompletableFuture.completedFuture(listOf("Value", "Second value"))
    private val subjectGateway = mockk<QueryGateway>()
    private val subscriptionPublisher = mockk<Publisher<UpdateResponseType>>()
    private val streamingPublisher = mockk<Publisher<UpdateResponseType>>()

    @BeforeTest
    fun before() {
        every { subjectGateway.query(exampleQuery, String::class.java) } returns instanceReturnValue
        every { subjectGateway.queryMany(exampleQuery, String::class.java) } returns listReturnValue
        every { subjectGateway.subscriptionQuery(exampleQuery, UpdateResponseType::class.java) } returns subscriptionPublisher
        every { subjectGateway.streamingQuery(exampleQuery, UpdateResponseType::class.java) } returns streamingPublisher
    }

    @AfterTest
    fun after() {
        clearMocks(subjectGateway)
    }

    @Test
    fun `Query should invoke query method with correct generic parameters`() {
        val queryResult = subjectGateway.query<String>(query = exampleQuery)
        assertSame(instanceReturnValue, queryResult)
        verify(exactly = 1) { subjectGateway.query(exampleQuery, String::class.java) }
    }

    @Test
    fun `Query should invoke query method and not require explicit generic types`() {
        val queryResult: CompletableFuture<String> = subjectGateway.query(query = exampleQuery)
        assertSame(instanceReturnValue, queryResult)
        verify(exactly = 1) { subjectGateway.query(exampleQuery, String::class.java) }
    }

    @Test
    fun `QueryMany should invoke queryMany method with correct generic parameters`() {
        val queryResult = subjectGateway.queryMany<String>(query = exampleQuery)
        assertSame(listReturnValue, queryResult)
        verify(exactly = 1) { subjectGateway.queryMany(exampleQuery, String::class.java) }
    }

    @Test
    fun `QueryMany should invoke queryMany method and not require explicit generic types`() {
        val queryResult: CompletableFuture<List<String>> = subjectGateway.queryMany(query = exampleQuery)
        assertSame(listReturnValue, queryResult)
        verify(exactly = 1) { subjectGateway.queryMany(exampleQuery, String::class.java) }
    }

    @Test
    fun `SubscriptionQuery should invoke subscriptionQuery method with correct generic parameters`() {
        val result = subjectGateway.subscriptionQuery<UpdateResponseType>(query = exampleQuery)
        assertSame(subscriptionPublisher, result)
        verify(exactly = 1) { subjectGateway.subscriptionQuery(exampleQuery, UpdateResponseType::class.java) }
    }

    @Test
    fun `SubscriptionQuery should invoke subscriptionQuery method and not require explicit generic types`() {
        val result: Publisher<UpdateResponseType> = subjectGateway.subscriptionQuery(query = exampleQuery)
        assertSame(subscriptionPublisher, result)
        verify(exactly = 1) { subjectGateway.subscriptionQuery(exampleQuery, UpdateResponseType::class.java) }
    }

    @Test
    fun `StreamingQuery should invoke streamingQuery method with correct generic parameters`() {
        val result = subjectGateway.streamingQuery<UpdateResponseType>(query = exampleQuery)
        assertSame(streamingPublisher, result)
        verify(exactly = 1) { subjectGateway.streamingQuery(exampleQuery, UpdateResponseType::class.java) }
    }

    @Test
    fun `StreamingQuery should invoke streamingQuery method and not require explicit generic types`() {
        val result: Publisher<UpdateResponseType> = subjectGateway.streamingQuery(query = exampleQuery)
        assertSame(streamingPublisher, result)
        verify(exactly = 1) { subjectGateway.streamingQuery(exampleQuery, UpdateResponseType::class.java) }
    }
}

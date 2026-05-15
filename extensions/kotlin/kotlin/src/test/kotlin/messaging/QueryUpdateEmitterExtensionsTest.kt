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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.axonframework.extension.kotlin.ExampleQuery
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Tests [org.axonframework.messaging.queryhandling.QueryUpdateEmitter] extensions.
 *
 * @author Stefan Andjelkovic
 */
internal class QueryUpdateEmitterExtensionsTest {
    private val subjectEmitter = mockk<QueryUpdateEmitter>()
    private val exampleQuery = ExampleQuery(2)
    private val exampleUpdatePayload: String = "Updated"

    @BeforeTest
    fun before() {
        every { subjectEmitter.emit(ExampleQuery::class.java, match { it.test(exampleQuery) }, exampleUpdatePayload) } returns Unit
    }

    @Test
    fun `Emit extension should invoke correct emit method with a class parameter`() {
        subjectEmitter.emit<ExampleQuery, String>(exampleUpdatePayload) { it.value == exampleQuery.value }
        verify { subjectEmitter.emit(ExampleQuery::class.java, match { it.test(exampleQuery) }, exampleUpdatePayload) }
    }
}

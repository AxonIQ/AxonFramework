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

package extensions.kotlin.testing

// tag::fixture-test[]
import org.axonframework.extension.kotlin.test.exception
import org.axonframework.extension.kotlin.test.whenever
import org.axonframework.test.fixture.AxonTestFixture
import org.junit.jupiter.api.Test

class CardTest {

    private lateinit var fixture: AxonTestFixture

    @Test
    fun `issuing an existing card fails`() {
        fixture.given()
            .event(CardIssued("card-1"))
            .whenever(IssueCard("card-1"))
            .then()
            .exception(IllegalStateException::class, "Card already issued")
    }
}
// end::fixture-test[]

class CardIssued(val id: String)

class IssueCard(val id: String)

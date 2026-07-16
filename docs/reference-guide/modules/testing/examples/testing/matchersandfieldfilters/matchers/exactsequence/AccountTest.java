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
package testing.matchersandfieldfilters.matchers.exactsequence;

import testing.matchersandfieldfilters.fixtures.CompleteOrderCommand;
import testing.matchersandfieldfilters.fixtures.OrderCompletedEvent;
import testing.matchersandfieldfilters.fixtures.PaymentProcessedEvent;

// tag::exact-sequence-of-exact-classes[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

import static org.axonframework.test.matchers.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void exactSequenceOfExactClasses() {
        fixture.when()
               .command(new CompleteOrderCommand("order-1"))
               .then()
               .eventsSatisfy(events -> assertThat(events, exactSequenceOf(
                       messageWithPayload(exactClassOf(OrderCompletedEvent.class)),
                       messageWithPayload(exactClassOf(PaymentProcessedEvent.class)),
                       andNoMore()
               )));
    }
}
// end::exact-sequence-of-exact-classes[]

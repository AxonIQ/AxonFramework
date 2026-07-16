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
package testing.basictesting.then.expect;

import testing.basictesting.fixtures.AccountBalance;
import testing.basictesting.fixtures.AccountClosedEvent;
import testing.basictesting.fixtures.GetBalanceQuery;

// tag::then-expect[]
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               // ...
               .when()
               .event(new AccountClosedEvent("account-1"))
               .then()
               .expect(config -> {
                   AccountBalance balance =
                           config.getComponent(QueryGateway.class)
                                 .query(
                                         new GetBalanceQuery("account-1"),
                                         AccountBalance.class
                                 )
                                 .join();
                   assertEquals(400.00, balance.amount());
               });
    }
}
// end::then-expect[]

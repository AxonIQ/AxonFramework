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
package migration.paths.testfixtures.then;

import migration.paths.testfixtures.fixtures.AxonConfig;
import migration.paths.testfixtures.fixtures.CardIssuedEvent;
import migration.paths.testfixtures.fixtures.CardRedeemedEvent;
import migration.paths.testfixtures.fixtures.IssueCardCommand;
import migration.paths.testfixtures.fixtures.RedeemCardCommand;

// tag::then-phase[]
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class GiftCardTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        ApplicationConfigurer configurer = AxonConfig.appConfigurer();
        fixture = AxonTestFixture.with(configurer);
    }

    @Test
    void expectEvents() {
        fixture.given()
               .event(new CardIssuedEvent("card-1", 100))
               .when()
               .command(new RedeemCardCommand("card-1", 30))
               .then()
               .events(new CardRedeemedEvent("card-1", 30));
    }

    @Test
    void expectNoEvents() {
        fixture.given()
               .event(new CardIssuedEvent("card-1", 100))
               .when()
               .command(new RedeemCardCommand("card-1", 0))
               .then()
               .noEvents();
    }

    @Test
    void expectException() {
        fixture.given()
               .event(new CardIssuedEvent("card-1", 100))
               .when()
               .command(new RedeemCardCommand("card-1", 200))
               .then()
               .exception(IllegalStateException.class);
    }

    @Test
    void expectSuccessfulHandlerExecution() {
        fixture.given()
               .event(new CardIssuedEvent("card-1", 100))
               .when()
               .command(new RedeemCardCommand("card-1", 30))
               .then()
               .success();
    }

    @Test
    void expectResultMessagePayload() {
        fixture.given()
               .noPriorActivity()
               .when()
               .command(new IssueCardCommand("card-1", 100))
               .then()
               .resultMessagePayload("card-1");
    }
}
// end::then-phase[]

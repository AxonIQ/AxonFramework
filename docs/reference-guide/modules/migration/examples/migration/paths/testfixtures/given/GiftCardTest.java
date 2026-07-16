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
package migration.paths.testfixtures.given;

import migration.paths.testfixtures.fixtures.AxonConfig;
import migration.paths.testfixtures.fixtures.CardIssuedEvent;
import migration.paths.testfixtures.fixtures.CardRedeemedEvent;
import migration.paths.testfixtures.fixtures.IssueCardCommand;

// tag::given-phase[]
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

import java.util.List;

class GiftCardTest {

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        ApplicationConfigurer configurer = AxonConfig.appConfigurer();
        fixture = AxonTestFixture.with(configurer);
    }

    @Test
    void givenSingleEvent() {
        fixture.given()
               .event(new CardIssuedEvent("card-1", 100));
               // when...
    }

    @Test
    void givenMultipleEvents() {
        fixture.given()
               .events(new CardIssuedEvent("card-1", 100),
                       new CardRedeemedEvent("card-1", 20));
               // when...
    }

    @Test
    void givenEventsAsList() {
        fixture.given()
               .events(List.of(new CardIssuedEvent("card-1", 100),
                               new CardRedeemedEvent("card-1", 20)));
               // when...
    }

    @Test
    void givenCommands() {
        fixture.given()
               .command(new IssueCardCommand("card-1", 100));
               // when...
    }

    @Test
    void givenNoPriorActivity() {
        fixture.given()
               .noPriorActivity();
               // when...
    }
}
// end::given-phase[]

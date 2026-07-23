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
package migration.paths.testfixtures.when;

import migration.paths.testfixtures.fixtures.AxonConfig;
import migration.paths.testfixtures.fixtures.CardIssuedEvent;
import migration.paths.testfixtures.fixtures.RedeemCardCommand;

// tag::when-phase[]
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.messaging.core.Metadata;
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
    void whenCommand() {
        fixture.given()
               .event(new CardIssuedEvent("card-1", 100))
               .when()
               .command(new RedeemCardCommand("card-1", 30));
               // then...
    }

    @Test
    void whenCommandWithMetadata() {
        fixture.given()
               .event(new CardIssuedEvent("card-1", 100))
               .when()
               .command(new RedeemCardCommand("card-1", 30),
                        Metadata.with("userId", "user-123"));
               // then...
    }
}
// end::when-phase[]

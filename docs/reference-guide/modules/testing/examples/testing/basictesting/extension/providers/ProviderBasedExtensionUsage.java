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
package testing.basictesting.extension.providers;

import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.test.extension.AxonFrameworkExtension;
import org.axonframework.test.extension.AxonTestFixtureProvider;
import org.axonframework.test.extension.ProvidedAxonTestFixture;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// Referenced by, but not shown alongside, the tagged snippet below.
class SpecificFixtureProvider implements AxonTestFixtureProvider {
    @Override
    public AxonTestFixture get() {
        return AxonTestFixture.with(EventSourcingConfigurer.create());
    }
}

// tag::junit-extension-providers[]
@ExtendWith(AxonFrameworkExtension.class)
@ProvidedAxonTestFixture(GlobalFixtureProvider.class) // <1>
class AccountEntityTest {

    @Test // <2>
    void testUsingGlobalProvider(AxonTestFixture fixture) {
        // This test will use the GlobalFixtureProvider to create the fixture instance
    }

    @Test
    @ProvidedAxonTestFixture(SpecificFixtureProvider.class) // <3>
    void testUsingSpecificProvider(AxonTestFixture fixture) {
        // This test will use the SpecificFixtureProvider to create the fixture instance
    }
}

class GlobalFixtureProvider implements AxonTestFixtureProvider {
    @Override
    public AxonTestFixture get() {
        return AxonTestFixture.with(EventSourcingConfigurer.create());
    }
}

// end::junit-extension-providers[]

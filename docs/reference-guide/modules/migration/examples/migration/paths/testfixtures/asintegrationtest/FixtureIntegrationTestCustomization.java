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
package migration.paths.testfixtures.asintegrationtest;

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;

/**
 * Wraps the "fixture customization" statement in a method taking an already-built {@code ApplicationConfigurer},
 * matching how the snippet is introduced in the surrounding documentation text; not otherwise elaborated in this
 * example.
 */
class FixtureIntegrationTestCustomization {

    void example(ApplicationConfigurer configurer) {
        // tag::fixture-as-integration-test[]
        AxonTestFixture fixture = AxonTestFixture.with(
                configurer,
                customization -> customization.asIntegrationTest()
        );
        // end::fixture-as-integration-test[]
    }
}

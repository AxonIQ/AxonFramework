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

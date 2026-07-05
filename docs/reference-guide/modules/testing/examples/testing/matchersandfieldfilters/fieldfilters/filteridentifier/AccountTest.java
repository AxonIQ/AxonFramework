package testing.matchersandfieldfilters.fieldfilters.filteridentifier;

// tag::filter-identifier-fields[]
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

import java.util.UUID;

class AccountTest {

    private ApplicationConfigurer configurer;

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = AxonTestFixture.with(
                configurer,
                customization -> customization.registerFieldFilter(
                        field -> !field.getType().equals(UUID.class)
                                && !field.getName().toLowerCase().contains("id")
                )
        );
    }
}
// end::filter-identifier-fields[]

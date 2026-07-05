package testing.matchersandfieldfilters.fieldfilters.registerignoredfield;

import testing.matchersandfieldfilters.fieldfilters.AccountCreatedEvent;

// tag::register-ignored-field[]
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private ApplicationConfigurer configurer;

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = AxonTestFixture.with(
                configurer,
                customization -> customization.registerIgnoredField(
                        AccountCreatedEvent.class, "accountId"
                )
        );
    }
}
// end::register-ignored-field[]

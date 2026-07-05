package testing.matchersandfieldfilters.fieldfilters.registerfieldfilter;

// tag::register-field-filter[]
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
                customization -> customization.registerFieldFilter(
                        field -> !field.getName().equals("accountId")
                )
        );
    }
}
// end::register-field-filter[]

package testing.matchersandfieldfilters.fieldfilters.filtertimestamp;

// tag::filter-timestamp-fields[]
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

import java.time.Instant;

class AccountTest {

    private ApplicationConfigurer configurer;

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = AxonTestFixture.with(
                configurer,
                customization -> customization.registerFieldFilter(field -> {
                    String name = field.getName();
                    return !(name.contains("timestamp")
                            || name.contains("createDate")
                            || name.contains("updateDate"))
                            && !field.getType().equals(Instant.class);
                })
        );
    }
}
// end::filter-timestamp-fields[]

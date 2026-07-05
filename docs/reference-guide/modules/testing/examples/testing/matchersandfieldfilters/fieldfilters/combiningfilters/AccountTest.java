package testing.matchersandfieldfilters.fieldfilters.combiningfilters;

import testing.matchersandfieldfilters.fieldfilters.AccountCreatedEvent;
import testing.matchersandfieldfilters.fixtures.OrderCreatedEvent;

// tag::combining-filters[]
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;
import org.axonframework.test.matchers.NonTransientFieldsFilter;
import org.junit.jupiter.api.*;

class AccountTest {

    private ApplicationConfigurer configurer;

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = AxonTestFixture.with(configurer, AccountTest::registerFilters);
    }

    private static AxonTestFixture.Customization registerFilters(
            AxonTestFixture.Customization customization
    ) {
        return customization.registerIgnoredField(AccountCreatedEvent.class, "accountId")
                            .registerIgnoredField(OrderCreatedEvent.class, "orderId")
                            .registerFieldFilter(NonTransientFieldsFilter.instance())
                            .registerFieldFilter(field -> !field.getName().contains("internal"));
    }
}
// end::combining-filters[]

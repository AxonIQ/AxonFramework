package testing.matchersandfieldfilters.fieldfilters.filterannotated;

// tag::filter-annotated-fields[]
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@interface IgnoreInTest {
    // Annotation is named as-s for example purposes only!
}

record AccountCreatedEvent(
        @IgnoreInTest String accountId,
        double amount
) {
}

class AccountTest {

    private ApplicationConfigurer configurer;

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = AxonTestFixture.with(
                configurer,
                customization -> customization.registerFieldFilter(
                        field -> !field.isAnnotationPresent(IgnoreInTest.class)
                )
        );
    }
}
// end::filter-annotated-fields[]

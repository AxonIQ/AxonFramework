package migration.paths.testfixtures.springboot;

// tag::fixture-setup-spring[]
import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class GiftCardTest {

    @Autowired
    private ApplicationConfigurer configurer;

    private AxonTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = AxonTestFixture.with(configurer);
    }
}
// end::fixture-setup-spring[]

package testing.basictesting.when.nothing;

// tag::when-nothing[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               // ...
               .when()
               .nothing();
               // then...
    }
}
// end::when-nothing[]

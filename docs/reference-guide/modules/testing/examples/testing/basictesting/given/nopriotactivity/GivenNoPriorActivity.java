package testing.basictesting.given.nopriotactivity;

// tag::given-no-prior-activity[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               .noPriorActivity();
               // when...
    }
}
// end::given-no-prior-activity[]

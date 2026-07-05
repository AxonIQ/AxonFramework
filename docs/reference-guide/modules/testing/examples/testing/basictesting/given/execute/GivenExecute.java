package testing.basictesting.given.execute;

// tag::given-execute[]
import org.axonframework.modelling.repository.Repository;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Test;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               .execute(config -> {
                   // Access any component from configuration
                   var repository = config.getComponent(Repository.class);
                   // Perform custom setup
               });
               // when...
    }
}
// end::given-execute[]

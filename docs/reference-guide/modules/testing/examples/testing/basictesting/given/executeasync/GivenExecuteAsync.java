package testing.basictesting.given.executeasync;

// tag::given-execute-async[]
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.*;

import java.util.concurrent.CompletableFuture;

class AccountTest {

    private AxonTestFixture fixture;

    @Test
    void test() {
        fixture.given()
               .executeAsync(config -> {
                   // Return CompletableFuture
                   return CompletableFuture.supplyAsync(() -> {
                       // Async setup logic
                       return null;
                   });
               });
               // when...
    }
}
// end::given-execute-async[]

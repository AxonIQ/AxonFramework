package testing.basictesting.extension.basic;

import testing.basictesting.fixtures.Account;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.test.extension.AxonFrameworkExtension;
import org.axonframework.test.extension.AxonTestFixtureProvider;
import org.axonframework.test.extension.ProvidedAxonTestFixture;
import org.axonframework.test.fixture.AxonTestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// tag::junit-extension-basic[]
@ExtendWith(AxonFrameworkExtension.class) // <1>
class AccountEntityTest {

    @ProvidedAxonTestFixture // <2>
    private AxonTestFixtureProvider provider = () -> AxonTestFixture.with(
            EventSourcingConfigurer.create().registerEntity(
                EventSourcedEntityModule.autodetected(
                        String.class, Account.class)));


    @Test
    void accountLifecycleReactsAsExpected(AxonTestFixture fixture) { // <3>
        //....
    }
}
// end::junit-extension-basic[]

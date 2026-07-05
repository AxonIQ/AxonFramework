package commands.configuration.replacement;

// tag::component-replacement[]
import org.axonframework.messaging.commandhandling.SimpleCommandBus;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;

public class AxonConfig {

    public static void main(String[] args) {
        MessagingConfigurer.create().registerCommandBus(
                config -> new SimpleCommandBus(
                        config.getComponent(UnitOfWorkFactory.class)
                )
        );
    }
}
// end::component-replacement[]

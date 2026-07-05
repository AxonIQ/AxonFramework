package commands.infrastructure.commandbus.springboot;

import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.SimpleCommandBus;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// tag::command-bus-spring[]
@Configuration
public class AxonConfig {

    @Bean
    public CommandBus commandBus(UnitOfWorkFactory unitOfWorkFactory) {
        return new SimpleCommandBus(unitOfWorkFactory);
    }
}
// end::command-bus-spring[]

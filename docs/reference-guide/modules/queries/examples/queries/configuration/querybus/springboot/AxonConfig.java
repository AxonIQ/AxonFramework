package queries.configuration.querybus.springboot;

// tag::query-bus-springboot[]
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.SimpleQueryBus;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;

@Configuration
public class AxonConfig {

    @Bean
    public QueryBus queryBus(UnitOfWorkFactory unitOfWorkFactory) {
        return new SimpleQueryBus(unitOfWorkFactory);
    }
}
// end::query-bus-springboot[]

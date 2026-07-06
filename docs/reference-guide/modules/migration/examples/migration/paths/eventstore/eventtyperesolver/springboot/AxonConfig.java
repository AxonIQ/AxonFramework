package migration.paths.eventstore.eventtyperesolver.springboot;

// tag::event-type-resolver-springboot[]
import org.axonframework.eventsourcing.eventstore.EventTypeResolver;
import org.axonframework.eventsourcing.eventstore.jpa.AggregateBasedJpaEventStorageEngineConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AxonConfig {

    @Bean
    public AggregateBasedJpaEventStorageEngineConfiguration storageEngineConfiguration() {
        return AggregateBasedJpaEventStorageEngineConfiguration.DEFAULT
                .eventTypeResolver(
                    EventTypeResolver.withDefaultVersion("0.0.1")
                );
    }
}
// end::event-type-resolver-springboot[]

package migration.paths.dlq.springfactory;

// tag::spring-dlq-factory[]
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import io.axoniq.framework.messaging.eventhandling.deadletter.SequencedDeadLetterQueueFactory;
import io.axoniq.framework.messaging.eventhandling.deadletter.jpa.JpaSequencedDeadLetterQueue;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DlqConfig {

    @Bean
    SequencedDeadLetterQueueFactory myDeadLetterQueueFactory(
            EntityManagerFactory entityManagerFactory,
            EventConverter eventConverter,
            GeneralConverter converter
    ) {
        return (processingGroup, config) ->
                JpaSequencedDeadLetterQueue.builder()
                        .processingGroup(processingGroup)
                        .maxSequences(256)
                        .maxSequenceSize(256)
                        .transactionalExecutorProvider(
                                new JpaTransactionalExecutorProvider(entityManagerFactory))
                        .eventConverter(eventConverter)
                        .genericConverter(converter)
                        .build();
    }
}
// end::spring-dlq-factory[]

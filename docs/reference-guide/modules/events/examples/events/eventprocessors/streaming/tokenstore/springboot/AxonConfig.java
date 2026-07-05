package events.eventprocessors.streaming.tokenstore.springboot;

// tag::token-store-spring-boot-bean[]
import jakarta.persistence.EntityManagerFactory;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.messaging.core.unitofwork.transaction.jpa.JpaTransactionalExecutorProvider;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.jpa.JpaTokenStoreConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AxonConfig {

    @Bean
    public TokenStore customTokenStore(EntityManagerFactory entityManagerFactory,
                                       GeneralConverter converter) {
        return new JpaTokenStore(new JpaTransactionalExecutorProvider(entityManagerFactory), converter, JpaTokenStoreConfiguration.DEFAULT);
    }
}
// end::token-store-spring-boot-bean[]

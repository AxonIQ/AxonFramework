package migration.paths.serializers;

// tag::spring-configuration[]
import org.axonframework.conversion.DelegatingGeneralConverter;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import tools.jackson.databind.ObjectMapper;

@Configuration
public class ConversionConfig {

    @Bean
    @Primary
    public GeneralConverter converter(ObjectMapper objectMapper) {
        return new DelegatingGeneralConverter(new JacksonConverter(objectMapper));
    }

    @Bean
    public MessageConverter messageConverter(GeneralConverter generalConverter) {
        return new DelegatingMessageConverter(generalConverter);
    }

    @Bean
    public EventConverter eventConverter(MessageConverter messageConverter) {
        return new DelegatingEventConverter(messageConverter);
    }
}
// end::spring-configuration[]

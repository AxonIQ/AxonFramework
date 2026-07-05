package root.conversion.configuration.springboot;

import org.axonframework.conversion.Converter;
import org.axonframework.conversion.DelegatingGeneralConverter;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// tag::converter-configuration-springboot[]
@Configuration
public class ConverterConfiguration {

   @Bean
   public GeneralConverter converter() {
       return new DelegatingGeneralConverter(new JacksonConverter());
   }

   @Bean
   public MessageConverter messageConverter(Converter converter) {
      return new DelegatingMessageConverter(converter);
   }

   @Bean
   public EventConverter eventConverter(MessageConverter messageConverter) {
      return new DelegatingEventConverter(messageConverter);
   }
}
// end::converter-configuration-springboot[]

/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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

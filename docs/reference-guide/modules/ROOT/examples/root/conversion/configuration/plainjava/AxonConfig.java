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
package root.conversion.configuration.plainjava;

// tag::converter-configuration[]
import org.axonframework.conversion.DelegatingGeneralConverter;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;

class AxonConfig {

    public void converterConfiguration(MessagingConfigurer configurer) {

        configurer
                // Register the general converter
                .componentRegistry(cr -> cr.registerComponent(
                        GeneralConverter.class,
                        config -> new DelegatingGeneralConverter(new JacksonConverter())
                ))
                // Register the message converter (wraps the general converter)
                .componentRegistry(cr -> cr.registerComponent(
                        MessageConverter.class,
                        config -> new DelegatingMessageConverter(config.getComponent(GeneralConverter.class))
                ))
                // Register the event converter (wraps the message converter)
                .componentRegistry(cr -> cr.registerComponent(
                        EventConverter.class,
                        config -> new DelegatingEventConverter(config.getComponent(MessageConverter.class))
                ));
    }
}
// end::converter-configuration[]

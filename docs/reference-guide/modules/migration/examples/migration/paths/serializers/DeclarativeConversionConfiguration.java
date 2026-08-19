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
package migration.paths.serializers;

// The imports are indented to the depth of the nested method below, so that the
// indent=0 normalization of the include renders both regions flush left.
// tag::declarative-configuration-import[]
    import org.axonframework.conversion.Converter;
    import org.axonframework.conversion.DelegatingGeneralConverter;
    import org.axonframework.conversion.GeneralConverter;
    import org.axonframework.conversion.jackson.JacksonConverter;
    import org.axonframework.messaging.core.configuration.MessagingConfigurer;
    import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
    import org.axonframework.messaging.core.conversion.MessageConverter;
    import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
    import org.axonframework.messaging.eventhandling.conversion.EventConverter;

// end::declarative-configuration-import[]
import tools.jackson.databind.ObjectMapper;

class DeclarativeConversionConfiguration {

    // tag::declarative-configuration[]
    public void configure(MessagingConfigurer configurer, ObjectMapper objectMapper) {
        configurer.componentRegistry(registry -> {
            Converter generalConverter = new JacksonConverter(objectMapper);
            registry.registerComponent(GeneralConverter.class, c -> new DelegatingGeneralConverter(generalConverter));
            registry.registerComponent(MessageConverter.class, c -> new DelegatingMessageConverter(generalConverter));
            registry.registerComponent(EventConverter.class, c -> new DelegatingEventConverter(generalConverter));
        });
    }
    // end::declarative-configuration[]
}

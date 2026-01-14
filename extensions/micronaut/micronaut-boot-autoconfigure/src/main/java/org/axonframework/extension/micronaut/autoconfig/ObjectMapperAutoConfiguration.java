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

package org.axonframework.extension.micronaut.autoconfig;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.axonframework.conversion.json.JacksonConverter;
import org.axonframework.extension.micronaut.data.JacksonPageDeserializer;
import org.axonframework.extension.micronaut.ConverterProperties;
import org.springframework.data.domain.Page;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * Autoconfiguration that constructs a default {@link ObjectMapper}, typically to be used by a
 * {@link JacksonConverter}.
 *
 * @author Steven van Beelen
 * @author Theo Emanuelsson
 * @since 3.4.0
 */
@AutoConfiguration
@AutoConfigureBefore({AxonAutoConfiguration.class, CBORMapperAutoConfiguration.class})
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration")
@ConditionalOnClass(name = "com.fasterxml.jackson.databind.ObjectMapper")
@EnableConfigurationProperties(value = ConverterProperties.class)
public class ObjectMapperAutoConfiguration {

    /**
     * Returns the default Axon Framework {@link ObjectMapper}, if required.
     * <p>
     * This {@code ObjectMapper} bean is only created when there is no other {@code ObjectMapper} bean present
     * <b>and</b> whenever the user specified either the
     * {@link ConverterProperties.ConverterType#DEFAULT} or
     * {@link ConverterProperties.ConverterType#JACKSON} {@code ConverterType}.
     *
     * @return The default Axon Framework {@link ObjectMapper}, if required.
     */
    @Bean("defaultAxonObjectMapper")
    @ConditionalOnMissingBean
    @Conditional(JacksonConfiguredCondition.class)
    public ObjectMapper defaultAxonObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    /**
     * Returns a Jackson {@link Module} that provides a custom deserializer for the Spring Data {@link Page} interface.
     * <p>
     * This {@code Module} bean is only created when the Spring Data {@link Page} interface is on the classpath
     * <b>and</b> whenever the user specified either the {@link ConverterProperties.ConverterType#DEFAULT} or
     * {@link ConverterProperties.ConverterType#JACKSON} {@code ConverterType}. The module deserializes {@link Page}
     * instances into {@link org.springframework.data.domain.PageImpl PageImpl} objects.
     *
     * @return A Jackson {@link Module} that deserializes {@link Page} into
     *         {@link org.springframework.data.domain.PageImpl PageImpl}.
     * @see JacksonPageDeserializer
     * @since 5.1.0
     */
    @Bean
    @ConditionalOnClass(Page.class)
    @Conditional(JacksonConfiguredCondition.class)
    public Module springDataPageJacksonModule() {
        return new SimpleModule()
                .addDeserializer(Page.class, new JacksonPageDeserializer());
    }

    /**
     * An {@link AnyNestedCondition} implementation, to support the following use cases:
     * <ul>
     *     <li>The {@code general} converter property is not set. This means Axon defaults to Jackson</li>
     *     <li>The {@code general} converter property is set to {@code default}. This means Jackson will be used</li>
     *     <li>The {@code general} converter property is set to {@code jackson}</li>
     *     <li>The {@code messages} converter property is set to {@code jackson}</li>
     *     <li>The {@code events} converter property is set to {@code jackson}</li>
     * </ul>
     */
    private static class JacksonConfiguredCondition extends AnyNestedCondition {

        public JacksonConfiguredCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @SuppressWarnings("unused")
        @ConditionalOnProperty(name = "axon.converter.general", havingValue = "default", matchIfMissing = true)
        static class GeneralDefaultCondition {

        }

        @SuppressWarnings("unused")
        @ConditionalOnProperty(name = "axon.converter.general", havingValue = "jackson", matchIfMissing = true)
        static class GeneralJacksonCondition {

        }

        @SuppressWarnings("unused")
        @ConditionalOnProperty(name = "axon.converter.messages", havingValue = "jackson")
        static class MessagesJacksonCondition {

        }

        @SuppressWarnings("unused")
        @ConditionalOnProperty(name = "axon.converter.events", havingValue = "jackson")
        static class EventsJacksonCondition {

        }
    }
}

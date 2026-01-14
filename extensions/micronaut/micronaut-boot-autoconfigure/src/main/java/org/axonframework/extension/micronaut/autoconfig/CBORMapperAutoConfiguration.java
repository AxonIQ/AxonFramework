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

import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import org.axonframework.conversion.json.JacksonConverter;
import org.axonframework.extension.micronaut.ConverterProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Autoconfiguration that constructs a default {@link CBORMapper}, typically to be used by a
 * {@link JacksonConverter}.
 *
 * @author Mitchell Herrijgers
 * @since 4.9.0
 */
@AutoConfiguration
@AutoConfigureBefore(ConverterAutoConfiguration.class)
@ConditionalOnClass(name = {"com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper"})
@EnableConfigurationProperties(value = ConverterProperties.class)
public class CBORMapperAutoConfiguration {

    /**
     * Returns the default Axon Framework {@link CBORMapper}, if required.
     * <p>
     * This {@code CBORMapper} bean is only created when there is no other {@code CBORMapper} bean present
     * <b>and</b> whenever the user specified the
     * {@link ConverterProperties.ConverterType#CBOR} {@code ConverterType}.
     *
     * @return The default Axon Framework {@link CBORMapper}, if required.
     */
    @Bean("defaultAxonCborMapper")
    @ConditionalOnMissingBean(CBORMapper.class)
    @ConditionalOnExpression("'${axon.converter.general}' == 'cbor' || '${axon.converter.events}' == 'cbor' || '${axon.converter.messages}' == 'cbor'")
    public CBORMapper defaultAxonCborMapper() {
        return (CBORMapper) new CBORMapper().findAndRegisterModules();
    }
}

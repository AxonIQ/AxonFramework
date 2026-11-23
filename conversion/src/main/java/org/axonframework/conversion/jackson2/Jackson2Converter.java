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

package org.axonframework.conversion.jackson2;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.conversion.ChainingContentTypeConverter;
import org.axonframework.conversion.ContentTypeConverter;
import org.axonframework.conversion.ConversionException;
import org.axonframework.conversion.Converter;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Objects;

/**
 * A {@link Converter} implementation for Jackson 2 that uses Jackson's {@link ObjectMapper} to convert objects into and
 * from a JSON format.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author Steven van Beelen
 * @since 2.2.0
 */
public class Jackson2Converter implements Converter {

    private static final Logger logger = LoggerFactory.getLogger(Jackson2Converter.class);

    private final ObjectMapper objectMapper;
    private final ChainingContentTypeConverter converter;

    /**
     * Constructs a {@code JacksonConverter} with a default {@link ObjectMapper} that
     * {@link ObjectMapper#findAndRegisterModules() finds and registers known modules}.
     */
    public Jackson2Converter() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    /**
     * Constructs a {@code JacksonConverter} with the given {@code objectMapper}.
     *
     * @param objectMapper The mapper used to convert objects into and from a JSON format.
     */
    public Jackson2Converter(ObjectMapper objectMapper) {
        this(objectMapper, new ChainingContentTypeConverter());
    }

    /**
     * Constructs a {@code JacksonConverter} with the given {@code objectMapper} and {@code converter}.
     * <p>
     * This constructor should only be used when a specific {@link ClassLoader} should be give to the
     * {@link ChainingContentTypeConverter} to ensure it loads the right set of
     * {@link ContentTypeConverter ContentTypeConverters}.
     *
     * @param objectMapper The mapper used to convert objects into and from a JSON format.
     * @param converter    The converter used for simpler conversions.
     */
    @Internal
    public Jackson2Converter(ObjectMapper objectMapper,
                             ChainingContentTypeConverter converter) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "The ObjectMapper may not be null.");
        this.converter = Objects.requireNonNull(converter, "The ChainingContentTypeConverter may not be null.");
        this.converter.registerConverter(new JsonNodeToByteArrayConverter(this.objectMapper));
        this.converter.registerConverter(new ByteArrayToJsonNodeConverter(this.objectMapper));
        this.converter.registerConverter(new JsonNodeToObjectNodeConverter());
        this.converter.registerConverter(new ObjectNodeToJsonNodeConverter());
    }

    @Nullable
    @Override
    public <T> T convert(@Nullable Object input,
                         Type targetType) {
        if (input == null) {
            try {
                return objectMapper.convertValue(null, objectMapper.constructType(targetType));
            } catch (IllegalArgumentException e) {
                throw new ConversionException(
                        "Exception when trying to convert null to '" + targetType.getTypeName() + "'", e
                );
            }
        }

        Class<?> sourceType = input.getClass();
        if (sourceType.equals(targetType)) {
            if (logger.isTraceEnabled()) {
                logger.trace("Casting given input since source and target type are identical.");
            }
            //noinspection unchecked
            return (T) input;
        }

        try {
            JavaType targetJavaType = objectMapper.constructType(targetType);
            if (converter.canConvert(sourceType, targetJavaType.getRawClass())) {
                if (logger.isTraceEnabled()) {
                    logger.trace(
                            "Converter [{}] will do the entire conversion from source [{}] to target [{}] for [{}].",
                            converter, sourceType, targetType, input
                    );
                }
                //noinspection unchecked
                return (T) converter.convert(input, targetJavaType.getRawClass());
            } else if (converter.canConvert(sourceType, byte[].class)) {
                if (logger.isTraceEnabled()) {
                    logger.trace("Converts input [{}] to byte[] before reading it into [{}].", input, targetJavaType);
                }
                return objectMapper.readValue(converter.convert(input, byte[].class), targetJavaType);
            } else if (converter.canConvert(byte[].class, targetJavaType.getRawClass())) {
                if (logger.isTraceEnabled()) {
                    logger.trace("Writes input [{}] as a byte[] before converting to [{}].", input, targetJavaType);
                }
                // Converting to byte[] from some input type.
                //noinspection unchecked
                return (T) converter.convert(objectMapper.writeValueAsBytes(input), targetJavaType.getRawClass());
            } else {
                if (logger.isTraceEnabled()) {
                    logger.trace("ObjectMapper [{}] will convert input [{}] to target type [{}].",
                                 objectMapper, input, targetJavaType);
                }
                // Unsure, let's see of the ObjectMapper can do this itself.
                return objectMapper.convertValue(input, targetJavaType);
            }
        } catch (IOException e) {
            throw new ConversionException(
                    "Exception when trying to convert object of type '" + sourceType.getTypeName() + "' to '"
                            + targetType.getTypeName() + "'", e
            );
        }
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeProperty("objectMapper", objectMapper);
        descriptor.describeProperty("chaining-content-type-converter", converter);
    }
}

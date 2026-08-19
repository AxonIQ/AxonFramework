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

package org.axonframework.conversion.jackson;

import org.jspecify.annotations.Nullable;
import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.conversion.ChainingContentTypeConverter;
import org.axonframework.conversion.ContentTypeConverter;
import org.axonframework.conversion.ConversionException;
import org.axonframework.conversion.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Type;
import java.util.Objects;

/**
 * A {@link Converter} implementation that uses Jackson's {@link ObjectMapper} to convert objects into and from a JSON
 * format.
 *
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author Steven van Beelen
 * @since 2.2.0
 */
public class JacksonConverter implements Converter {

    private static final Logger logger = LoggerFactory.getLogger(JacksonConverter.class);

    private static final ClassValue<Boolean> IS_JACKSON_2_TREE_NODE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                Class<?> jackson2JsonNodeType = Class.forName("com.fasterxml.jackson.databind.JsonNode",
                                                              false,
                                                              type.getClassLoader());
                return jackson2JsonNodeType.isAssignableFrom(type);
            } catch (ClassNotFoundException jackson2NotOnClasspath) {
                return false;
            }
        }
    };

    private final ObjectMapper objectMapper;
    private final ChainingContentTypeConverter converter;

    /**
     * Constructs a {@code JacksonConverter} with a default {@link ObjectMapper} constructed by the
     * {@link JsonMapper#builder()}, that
     * {@link JsonMapper.Builder#findAndAddModules() finds and registers known modules}.
     */
    public JacksonConverter() {
        this(JsonMapper.builder().findAndAddModules().build());
    }

    /**
     * Constructs a {@code JacksonConverter} with the given {@code objectMapper}.
     *
     * @param objectMapper The mapper used to convert objects into and from a JSON format.
     */
    public JacksonConverter(ObjectMapper objectMapper) {
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
    public JacksonConverter(ObjectMapper objectMapper,
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
            if (logger.isTraceEnabled()) {
                logger.trace("Input to convert is null, so returning null immediately.");
            }
            return null;
        }

        Class<?> sourceType = input.getClass();
        if (sourceType.equals(targetType)) {
            if (logger.isTraceEnabled()) {
                logger.trace("Casting given input since source and target type are identical.");
            }
            //noinspection unchecked
            return (T) input;
        }

        if (isForeignJacksonTreeNode(sourceType)) {
            throw new ConversionException("""
                    Cannot convert input of type '%s' using JacksonConverter (which uses Jackson 3, \
                    package 'tools.jackson.databind'). The input is a Jackson 2 tree node \
                    ('com.fasterxml.jackson.databind.JsonNode'), which Jackson 3 does not recognize \
                    as a tree and would silently introspect as a POJO, producing a wrong result. \
                    Serialize the input to a JSON String or byte[] before conversion, or use a \
                    Jackson 2-based Converter.""".formatted(sourceType.getName()));
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
        } catch (JacksonException e) {
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

    /**
     * Returns {@code true} when {@code type} is, or extends, Jackson 2's
     * {@code com.fasterxml.jackson.databind.JsonNode}. The Jackson 2 type is resolved through
     * {@code type}'s own class loader, so this class needs no compile-time dependency on Jackson 2;
     * when Jackson 2 is absent the input cannot be one of its tree nodes and {@code false} is returned.
     * The result is cached per input {@code type} through {@link #IS_JACKSON_2_TREE_NODE}.
     * <p>
     * Jackson 3's {@code ObjectMapper} does not recognize Jackson 2 tree nodes; it falls
     * back to bean introspection which produces a wrong map (keys like {@code isArray},
     * {@code isObject}, ... derived from {@code JsonNode}'s accessor methods). Detecting
     * these inputs early lets us replace silent wrong output with a clear failure.
     */
    private static boolean isForeignJacksonTreeNode(Class<?> type) {
        return IS_JACKSON_2_TREE_NODE.get(type);
    }
}

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

package org.axonframework.extension.micronaut;

import jakarta.annotation.Nonnull;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.json.JacksonConverter;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.queryhandling.QueryResponseMessage;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A group of properties that allows easy configuration of the
 * {@link Converter Converters} used for different types of objects that Axon needs to
 * convert.
 * <p>
 * The <b>general</b> {@code Converter} forms the baseline and is used to convert all objects that do not fall into more
 * specific categories. Typical examples include snapshots and other internal data that is not expected to be shared
 * with other systems. If no general {@code Converter} is specified, Axon defaults to the
 * {@link JacksonConverter}.
 * <p>
 * The <b>messages</b> {@code Converter}, if specified, takes precedence over the general converter for all
 * {@link org.axonframework.messaging.core.Message Messages} and, where relevant, their return values. This includes
 * commands, queries, and events, as well as
 * {@link CommandResultMessage CommandResultMessages} and
 * {@link QueryResponseMessage QueryResponseMessages}. To ensure the messages
 * {@code Converter} only applies to messages, it is wrapped in a
 * {@link DelegatingMessageConverter DelegatingMessageConverter}. When no message
 * {@code Converter} is specified, it defaults back to the general converter.
 * <p>
 * The <b>events</b> {@code Converter}, if specified, takes precedence over both the messages and general converters,
 * but applies <i>only</i> to the {@link EventMessage EventMessages} stored in the event
 * store and published on the event bus. To ensure it only applies to event messages, it is wrapped in a
 * {@link DelegatingEventConverter DelegatingEventConverter}. When no event
 * {@code Converter} is specified, it defaults back to the <b>messages</b> converter.
 *
 * @author Allard Buijze
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
@ConfigurationProperties("axon.converter")
public class ConverterProperties {

    /**
     * The type of {@link Converter} to use to convert any type for which no more
     * specific {@code Converter} is configured.
     * <p>
     * Defaults to the {@link ConverterType#DEFAULT default ConverterType}.
     */
    private ConverterType general = ConverterType.DEFAULT;

    /**
     * The type of {@link Converter} to use to convert the {@link Message#payload()} of
     * any type of {@link org.axonframework.messaging.core.Message}.
     * <p>
     * The constructed {@code Converter} will <b>always</b> be wrapped in a {@link DelegatingMessageConverter}, ensuring
     * the {@code Converter}
     * <b>only</b> deals with {@code Messages}.
     * <p>
     * Defaults to the <b>general</b> {@code Converter}.
     */
    private ConverterType messages = ConverterType.DEFAULT;

    /**
     * The type of {@link Converter} to use to convert the
     * {@link EventMessage#payload()} of {@link EventMessage EventMessages}.
     * <p>
     * The constructed {@code Converter} will <b>always</b> be wrapped in a {@link DelegatingEventConverter}, ensuring
     * the {@code Converter}
     * <b>only</b> deals with {@code EventMessages}.
     * <p>
     * Defaults to the <b>messages</b> {@code Converter} when set, or otherwise the <b>general</b> {@code Converter}.
     */
    private ConverterType events = ConverterType.DEFAULT;

    /**
     * The type of {@link Converter} to use to convert any type for which no more
     * specific {@code Converter} is configured.
     * <p>
     * Defaults to the {@link ConverterType#DEFAULT default ConverterType}.
     *
     * @return The {@link Converter} type to use for conversion of all kinds of
     * objects
     */
    @Nonnull
    public ConverterType getGeneral() {
        return general;
    }

    /**
     * Sets the type of {@link Converter} to use to convert any type for which no more
     * specific {@code Converter} is configured.
     * <p>
     * Defaults to the {@link ConverterType#DEFAULT default ConverterType}.
     *
     * @param converterType The converter type to use for converting any object.
     */
    public void setGeneral(@Nonnull ConverterType converterType) {
        this.general = converterType;
    }

    /**
     * The type of {@link Converter} to use to convert the {@link Message#payload()} of
     * any type of {@link org.axonframework.messaging.core.Message}.
     * <p>
     * The constructed {@code Converter} will <b>always</b> be wrapped in a {@link DelegatingMessageConverter}, ensuring
     * the {@code Converter}
     * <b>only</b> deals with {@code Messages}.
     * <p>
     * Defaults to the {@link #getGeneral() <b>general</b> Converter}.
     *
     * @return The type of {@link Converter} to use for
     * {@link Message#payload() Message payloads}.
     */
    @Nonnull
    public ConverterType getMessages() {
        return messages;
    }

    /**
     * Sets the type of {@link Converter} to use to convert the
     * {@link Message#payload()} of any type of {@link org.axonframework.messaging.core.Message}.
     * <p>
     * The constructed {@code Converter} will <b>always</b> be wrapped in a {@link DelegatingMessageConverter}, ensuring
     * the {@code Converter}
     * <b>only</b> deals with {@code Messages}.
     * <p>
     * Defaults to the <b>general</b> {@code Converter}.
     *
     * @param converterType The converter type to use for converting any {@link org.axonframework.messaging.core.Message}
     *                      {@link Message#payload()}.
     */
    public void setMessages(@Nonnull ConverterType converterType) {
        this.messages = converterType;
    }

    /**
     * The type of {@link Converter} to use to convert the
     * {@link EventMessage#payload()} of {@link EventMessage EventMessages}.
     * <p>
     * The constructed {@code Converter} will <b>always</b> be wrapped in a {@link DelegatingEventConverter}, ensuring
     * the {@code Converter}
     * <b>only</b> deals with {@code EventMessages}.
     * <p>
     * Defaults to the <b>messages</b> {@code Converter} when set, or otherwise the <b>general</b> {@code Converter}.
     *
     * @return The type of {@link Converter} to use for
     * {@link EventMessage#payload() EventMessage payloads}.
     */
    @Nonnull
    public ConverterType getEvents() {
        return events;
    }

    /**
     * Sets the type of {@link Converter} to use to convert the
     * {@link EventMessage#payload()} of {@link EventMessage EventMessages}.
     * <p>
     * The constructed {@code Converter} will <b>always</b> be wrapped in a {@link DelegatingEventConverter}, ensuring
     * the {@code Converter}
     * <b>only</b> deals with {@code EventMessages}.
     * <p>
     * Defaults to the <b>messages</b> {@code Converter} when set, or otherwise the <b>general</b> {@code Converter}.
     *
     * @param converterType The converter type to use for converting any
     *                      {@link EventMessage} {@link EventMessage#payload()}.
     */
    public void setEvents(@Nonnull ConverterType converterType) {
        this.events = converterType;
    }

    /**
     * Enumerates different possible standard {@link Converter Converters} available in
     * Axon Framework.
     */
    public enum ConverterType {
        /**
         * Uses Avro-based {@link Converter} to convert objects into bytes as specified
         * by Avro Specification using single object encoding.
         */
        AVRO,
        /**
         * Uses Jackson's {@link com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper} to convert objects into
         * CBOR.
         * <p>
         * This format is not human-readable, but can save on the size of (e.g.) {@link Message Messages}. When using
         * this {@link Converter}, make sure your tables are BLOBs, not CLOBs, as
         * converting the {@code byte[]} to a {@code String} will corrupt the data.
         */
        CBOR,
        /**
         * Uses Jackson's {@link com.fasterxml.jackson.databind.ObjectMapper} to convert objects into JSON.
         * <p>
         * Provides highly interoperable JSON output, but does require the objects to adhere to a certain structure. The
         * Jackson based serializer is generally suitable as a <b>messages</b>
         * {@link Converter}.
         */
        JACKSON,
        /**
         * Defines that the default serializer should be used.
         * <p>
         * For the <b>general</b> {@link Converter}, this means the
         * {@link JacksonConverter} is used. For the <b>messages</b>
         * {@code Converter}, this means the <b>general</b> {@code Converter} is used. Similarly, the <b>events</b>
         * {@code Converter} will default to the <b>messages</b> {@code Converter} (or the <b>general</b>
         * {@code Converter} if the <b>messages</b> {@code Converter} has not been specified).
         */
        DEFAULT
    }
}

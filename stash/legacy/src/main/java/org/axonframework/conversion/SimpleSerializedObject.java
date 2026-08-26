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

package org.axonframework.conversion;

import org.axonframework.common.Assert;

import java.util.Objects;

import static java.lang.String.format;

/**
 * SerializedObject implementation that takes all properties as constructor parameters.
 *
 * @param <T> The data type representing the serialized object
 * @author Allard Buijze
 * @since 2.0
 * TODO #3602 remove
 * @deprecated By shifting from the {@link Serializer} to the {@link Converter}, this exception becomes obsolete.
 */
@Deprecated(forRemoval = true, since = "5.0.0")
public class SimpleSerializedObject<T> implements SerializedObject<T> {

    private final T data;
    private final SerializedType type;
    private final Class<T> dataType;

    /**
     * Initializes a SimpleSerializedObject using given {@code data} and {@code serializedType}.
     *
     * @param data           The data of the serialized object
     * @param dataType       The type of data
     * @param serializedType The type description of the serialized object
     */
    public SimpleSerializedObject(T data, Class<T> dataType, SerializedType serializedType) {
        Assert.notNull(data, () -> "Data for a serialized object cannot be null");
        Assert.notNull(serializedType, () -> "The type identifier of the serialized object");
        this.data = data;
        this.dataType = dataType;
        this.type = serializedType;
    }

    /**
     * Initializes a SimpleSerializedObject using given {@code data} and a serialized type identified by given
     * {@code type} and {@code revision}.
     *
     * @param data     The data of the serialized object
     * @param dataType The type of data
     * @param type     The type identifying the serialized object
     * @param revision The revision of the serialized object
     */
    public SimpleSerializedObject(T data, Class<T> dataType, String type, String revision) {
        this(data, dataType, new SimpleSerializedType(type, revision));
    }

    @Override
    public T getData() {
        return data;
    }

    @Override
    public Class<T> getContentType() {
        return dataType;
    }

    @Override
    public SerializedType getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SimpleSerializedObject<?> that = (SimpleSerializedObject<?>) o;
        return Objects.equals(data, that.data) && Objects.equals(type, that.type) &&
                Objects.equals(dataType, that.dataType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(data, type, dataType);
    }

    @Override
    public String toString() {
        return format("SimpleSerializedObject [%s]", type);
    }
}

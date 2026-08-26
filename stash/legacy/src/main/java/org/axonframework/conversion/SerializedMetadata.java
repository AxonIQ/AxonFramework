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

import org.axonframework.messaging.core.Metadata;

import java.util.Objects;

/**
 * Represents the serialized form of a {@link Metadata} instance.
 *
 * @param <T> The data type representing the serialized object
 * @author Allard Buijze
 * @since 2.0
 * TODO #3602 remove
 * @deprecated By shifting from the {@link Serializer} to the {@link Converter}, this exception becomes obsolete.
 */
@Deprecated(forRemoval = true, since = "5.0.0")
public class SerializedMetadata<T> implements SerializedObject<T> {

    private static final String METADATA_CLASS_NAME = Metadata.class.getName();

    private final SimpleSerializedObject<T> delegate;

    /**
     * Construct an instance with given {@code bytes} representing the serialized form of a {@link Metadata}
     * instance.
     *
     * @param data     data representing the serialized form of a {@link Metadata} instance.
     * @param dataType The type of data
     */
    public SerializedMetadata(T data, Class<T> dataType) {
        delegate = new SimpleSerializedObject<>(data, dataType, METADATA_CLASS_NAME, null);
    }

    /**
     * Indicates whether the given {@code serializedObject} represents a serialized form of a Metadata object,
     * such as the ones created by this class (see {@link #SerializedMetadata(Object, Class)}.
     *
     * @param serializedObject The object to check for metadata
     * @return {@code true} if the serialized objects represents serialized metadata, otherwise
     *         {@code false}.
     */
    public static boolean isSerializedMetadata(SerializedObject<?> serializedObject) {
        return serializedObject != null
                && serializedObject.getType() != null
                && METADATA_CLASS_NAME.equals(serializedObject.getType().getName());
    }

    @Override
    public T getData() {
        return delegate.getData();
    }

    @Override
    public Class<T> getContentType() {
        return delegate.getContentType();
    }

    @Override
    public SerializedType getType() {
        return delegate.getType();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SerializedMetadata<?> that = (SerializedMetadata<?>) o;
        return Objects.equals(delegate, that.delegate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(delegate);
    }
}

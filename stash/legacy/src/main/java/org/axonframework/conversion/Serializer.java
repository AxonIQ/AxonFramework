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

import java.lang.reflect.Type;
import org.jspecify.annotations.Nullable;

/**
 * Interface describing a conversion mechanism. Implementations can serialize objects of given type {@code T} to an
 * output stream and read the object back in from an input stream.
 *
 * @author Allard Buijze
 * @since 1.2
 * @deprecated In favor of the simpler {@link Converter} interface.
 * TODO #3602 remove
 */
@Deprecated(forRemoval = true, since = "5.0.0")
public interface Serializer {

    /**
     * Serialize the given {@code object} into a Serialized Object containing the given {@code expectedRepresentation}.
     * <p/>
     * Use {@link #canSerializeTo(Class)} to detect whether the {@code expectedRepresentation} is supported by this
     * serializer.
     *
     * @param object                 The object to serialize
     * @param expectedRepresentation The expected data type representing the serialized object
     * @param <T>                    The expected data type representing the serialized object
     * @return the instance representing the serialized object.
     */
    @Deprecated
    <T> SerializedObject<T> serialize(@Nullable Object object, Class<T> expectedRepresentation);

    default <T> T convert(@Nullable Object source, Class<T> targetRepresentation) {
        return convert(source, (Type) targetRepresentation);
    }

    default <T> T convert(@Nullable Object source, Type targetRepresentation) {
        throw new UnsupportedOperationException("Not supported by this serializer");
    }

    /**
     * Indicates whether this Serializer is capable of serializing to the given {@code expectedRepresentation}.
     * <p/>
     * When {@code true}, this does *not* guarantee that the conversion and (optional) conversion will also succeed
     * when executed. For example, when a serializer produces a {@code byte[]} containing JSON, trying to convert to a
     * Dom4J Document will fail, even though this serializer has a converter to convert {@code byte[]} to Dom4J
     * instances.
     *
     * @param expectedRepresentation The type of data a Serialized Object should contain
     * @param <T>                    The type of data a Serialized Object should contain
     * @return {@code true} if the {@code expectedRepresentation} is supported, otherwise {@code false}.
     */
    @Deprecated
    <T> boolean canSerializeTo(Class<T> expectedRepresentation);

    /**
     * Deserializes the first object read from the given {@code bytes}. The {@code bytes} are not consumed from the
     * array or modified in any way. The resulting object instance is cast to the expected type.
     *
     * @param serializedObject the instance describing the type of object and the bytes providing the serialized data
     * @param <S>              The data type of the serialized object
     * @param <T>              The expected deserialized type
     * @return the serialized object, cast to the expected type
     * @throws ClassCastException if the first object in the stream is not an instance of &lt;T&gt;.
     */
    @Deprecated
    <S, T> T deserialize(SerializedObject<S> serializedObject);

    /**
     * Returns the class for the given type identifier. The result of this method must guarantee that the deserialized
     * SerializedObject with the given {@code type} is an instance of the returned Class.
     * <p/>
     * If a class cannot be resolved (i.e. because the class is not available on this JVM's classpath) this method
     * returns an {@link UnknownSerializedType} instance which proides access to the raw underlying data.
     *
     * @param type The type identifier of the object
     * @return the Class representing the type of the serialized Object
     */
    @Deprecated
    Class classForType(SerializedType type);

    /**
     * Returns the type identifier for the given class. This is the type identifier of the Serialized object as returned
     * by {@link #serialize(Object, Class)}.
     *
     * @param type Class representing the type of the serializable Object.
     * @return The type identifier of the object
     */
    @Deprecated
    SerializedType typeForClass(@Nullable Class type);

    /**
     * Returns the {@link Converter} used by this Serializer to convert between serialized representations. Generally,
     * this Converter depends on the type of data the serializer serializes to.
     *
     * @return the converter used by this Serializer
     */
    @Deprecated
    Converter getConverter();
}

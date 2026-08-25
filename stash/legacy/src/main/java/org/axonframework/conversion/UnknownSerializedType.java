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

/**
 * Class representing a serialized object of which there is no class available in the current class loader. This class
 * provides access to the raw underlying data, as well as any format supported by the serializer.
 *
 * @author Allard Buijze
 * @since 4.0
 * TODO #3602 remove
 * @deprecated By shifting from the {@link Serializer} to the {@link Converter}, this exception becomes obsolete.
 */
@Deprecated(forRemoval = true, since = "5.0.0")
public class UnknownSerializedType {

    private final Serializer serializer;
    private final SerializedObject<?> serializedObject;

    /**
     * Initialize the unknown type, using given {@code serializer} and {@code serializedObject}.
     *
     * @param serializer       the serializer attempting to deserialize the object
     * @param serializedObject the object being deserialized
     */
    public UnknownSerializedType(Serializer serializer, SerializedObject<?> serializedObject) {
        this.serializer = serializer;
        this.serializedObject = serializedObject;
    }

    /**
     * Indicates whether the given {@code desiredFormat} is supported as a representation for this type. More
     * specifically, it will consult the serializer that returned this instance whether it has a converter to convert
     * the serialized object's data to the desired type
     *
     * @param desiredFormat the format in which the serialized object is desired
     * @param <T>           the format in which the serialized object is desired
     * @return {@code true} if the format is supported, otherwise {@code false}
     */
    public <T> boolean supportsFormat(Class<T> desiredFormat) {
        Converter converter = serializer.getConverter();
        if (converter instanceof ChainingContentTypeConverter chainingConverter) {
            return chainingConverter.canConvert(serializedObject.getContentType(), desiredFormat);
        } else {
            return false;
        }
    }

    /**
     * Returns the data contained in the serialized object in the given {@code desiredFormat}, if supported. If the
     * desired format is unsupported, an exception is thrown, depending on the serializer.
     * <p>
     * To verify whether a format is supported, use {@link #supportsFormat(Class)}.
     *
     * @param desiredFormat the format in which the data is desired
     * @param <T>           the format in which the data is desired
     * @return the data in the desired format
     */
    public <T> T readData(Class<T> desiredFormat) {
        if (serializedObject.getContentType().equals(desiredFormat)) {
            return ((SerializedObject<T>) serializedObject).getData();
        }
        return new SimpleSerializedObject<>(serializer.getConverter()
                                                      .convert(serializedObject.getData(), desiredFormat),
                                            desiredFormat,
                                            serializedObject.getType()).getData();
    }

    /**
     * Returns the type of the serialized object, for which no class could be resolved.
     *
     * @return the type of the serialized object
     */
    public SerializedType serializedType() {
        return serializedObject.getType();
    }
}

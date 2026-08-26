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

import java.util.function.Supplier;

/**
 * Represents a serialized object that can be deserializedObjects upon request. Typically used as a wrapper class for
 * keeping a SerializedObject and its Serializer together.
 *
 * @param <T> The type of object contained in the serialized object
 * @author Allard Buijze
 * @author Frank Versnel
 * @since 2.0
 * TODO #3602 remove
 * @deprecated By shifting from the {@link Serializer} to the {@link Converter}, this exception becomes obsolete.
 */
@Deprecated(forRemoval = true, since = "5.0.0")
public class LazyDeserializingObject<T> {

    private final transient Serializer serializer;
    private final Supplier<SerializedObject<?>> serializedObject;
    private final Class<T> deserializedObjectType;
    private volatile transient T deserializedObject;

    /**
     * Creates an instance with the given {@code deserializedObject} object instance. Using this constructor will
     * ensure that no deserializing is required when invoking the {@link #getType()} or {@link #getObject()} methods.
     *
     * @param deserializedObject The deserialized object to return on {@link #getObject()}
     */
    @SuppressWarnings("unchecked")
    public LazyDeserializingObject(T deserializedObject) {
        Assert.notNull(deserializedObject, () -> "The given deserialized instance may not be null");
        this.serializedObject = null;
        this.serializer = null;
        this.deserializedObject = deserializedObject;
        this.deserializedObjectType = (Class<T>) deserializedObject.getClass();
    }

    /**
     * Creates an instance which will deserialize given {@code serializedObject} upon request. Use this constructor
     * if the {@code serializedObject} is already available and does not need to undergo a process of e.g.
     * upcasting.
     *
     * @param serializedObject The serialized payload of the message
     * @param serializer       The serializer to deserialize the payload data with
     */
    public LazyDeserializingObject(SerializedObject<?> serializedObject, Serializer serializer) {
        this(() -> serializedObject, serializedObject.getType(), serializer);
    }

    /**
     * Creates an instance which will get the supplied SerializedObject and deserialize it upon request. Use this
     * constructor if getting the SerializedObject is an 'expensive operation', e.g. if the SerializedObject still needs
     * to be upcasted.
     *
     * @param serializedObjectSupplier The supplier of the serialized object
     * @param serializedType           The type of the serialized object
     * @param serializer               The serializer to deserialize the payload data with
     */
    @SuppressWarnings("unchecked")
    public LazyDeserializingObject(Supplier<SerializedObject<?>> serializedObjectSupplier,
                                   SerializedType serializedType, Serializer serializer) {
        Assert.notNull(serializedObjectSupplier, () -> "The given serializedObjectSupplier may not be null");
        Assert.notNull(serializedType, () -> "The given serializedType may not be null");
        Assert.notNull(serializer, () -> "The given serializer may not be null");
        this.serializedObject = serializedObjectSupplier;
        this.serializer = serializer;
        this.deserializedObjectType = serializer.classForType(serializedType);
    }

    /**
     * Returns the class of the serialized object.
     *
     * @return the class of the serialized object
     */
    public Class<T> getType() {
        return deserializedObjectType;
    }

    /**
     * De-serializes the object and returns the result.
     *
     * @return the deserialized object
     */
    public T getObject() {
        if (!isDeserialized()) {
            deserializedObject = serializer.deserialize(serializedObject.get());
        }
        return deserializedObject;
    }

    /**
     * Indicates whether this object has already been deserialized. When this method returns {@code true}, the
     * {@link #getObject()} method is able to return a value without invoking the serializer.
     *
     * @return whether the contained object has been deserialized already.
     */
    public boolean isDeserialized() {
        return deserializedObject != null;
    }

    /**
     * Returns the serializer to deserialize this object
     *
     * @return the serializer to deserialize this object
     */
    public Serializer getSerializer() {
        return serializer;
    }

    /**
     * Returns the serialized object to deserialize upon request
     *
     * @return the serialized object to deserialize upon request
     */
    public SerializedObject<?> getSerializedObject() {
        return serializedObject.get();
    }
}

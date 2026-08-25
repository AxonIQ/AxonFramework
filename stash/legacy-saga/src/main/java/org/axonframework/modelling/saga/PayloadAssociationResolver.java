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

package org.axonframework.modelling.saga;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.property.Property;
import org.axonframework.common.property.PropertyAccessStrategy;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;

import java.lang.reflect.Executable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.String.format;

/**
 * Used to derive the value of an association property by looking it up the event message's payload.
 *
 * @author Sofia Guy Ang
 */
public class PayloadAssociationResolver implements AssociationResolver {

    private Map<String, Property<?>> propertyMap = new ConcurrentHashMap<>();

    /**
     * Validates that the association property name exists as checked with the payload type. This is done by attempting
     * to create a {@link Property}. It also caches the resulting {@link Property} instance.
     */
    @Override
    public <T> void validate(String associationPropertyName, MessageHandlingMember<T> handler) {
        getProperty(associationPropertyName, handler);
    }

    /**
     * Finds the association property value in the message's payload.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Object resolve(String associationPropertyName, EventMessage message,
                              MessageHandlingMember<T> handler) {
        return getProperty(associationPropertyName, handler).getValue(message.payload());
    }

    private <T> Property getProperty(String associationPropertyName, MessageHandlingMember<T> handler) {
        return propertyMap.computeIfAbsent(handler.payloadType().getCanonicalName() + associationPropertyName,
                                           k -> createProperty(associationPropertyName, handler));
    }

    private <T> Property createProperty(String associationPropertyName, MessageHandlingMember<T> handler) {
        if (associationPropertyName.isEmpty()) {
            throw new AxonConfigurationException(format(
                    "SagaEventHandler %s does not define an association property",
                    getHandlerName(handler)
            ));
        }

        Property<?> associationProperty = PropertyAccessStrategy.getProperty(handler.payloadType(),
                                                                             associationPropertyName);
        if (associationProperty == null) {
            throw new AxonConfigurationException(format(
                    "SagaEventHandler %s defines a property %s that is not defined on the Event it declares to handle (%s)",
                    getHandlerName(handler),
                    associationPropertyName,
                    handler.payloadType().getName()
            ));
        }
        return associationProperty;
    }

    private String getHandlerName(MessageHandlingMember<?> handler) {
        return handler.unwrap(Executable.class).map(Executable::toGenericString).orElse("unknown");
    }
}

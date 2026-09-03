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

import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;

/**
 * Used to derive the value of an association property by looking it up the event message's {@link
 * Metadata}.
 *
 * @author Sofia Guy Ang
 */
public class MetadataAssociationResolver implements AssociationResolver {

    /**
     * Does nothing because we can only check for existence of property in the metadata during event handling.
     */
    @Override
    public <T> void validate(String associationPropertyName, MessageHandlingMember<T> handler) {
        // Do nothing
    }

    /**
     * Finds the association property value by looking up the association property name in the event message's {@link
     * Metadata}.
     */
    @Override
    public <T> Object resolve(String associationPropertyName, EventMessage message,
                              MessageHandlingMember<T> handler) {
        return message.metadata().get(associationPropertyName);
    }
}

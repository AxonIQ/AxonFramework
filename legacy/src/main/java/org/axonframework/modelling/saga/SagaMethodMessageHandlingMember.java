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
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember;

/**
 * A data holder containing information of {@link SagaEventHandler} annotated methods.
 *
 * @author Allard Buijze
 * @author Sofia Guy Ang
 * @since 2.0
 */
public class SagaMethodMessageHandlingMember<T> extends WrappedMessageHandlingMember<T> {

    private final SagaCreationPolicy creationPolicy;
    private final String associationKey;
    private final String associationPropertyName;
    private final AssociationResolver associationResolver;

    /**
     * Creates a SagaMethodMessageHandler.
     *
     * @param creationPolicy          The creation policy for the handlerMethod
     * @param delegate                The message handler for the event
     * @param associationKey          The association key configured for this handler
     * @param associationPropertyName The association property name to look up in the message
     * @param associationResolver     The association resolver configured for this handler
     */
    public SagaMethodMessageHandlingMember(MessageHandlingMember<T> delegate,
                                           SagaCreationPolicy creationPolicy,
                                           String associationKey,
                                           String associationPropertyName,
                                           AssociationResolver associationResolver) {
        super(delegate);
        this.creationPolicy = creationPolicy;
        this.associationKey = associationKey;
        this.associationPropertyName = associationPropertyName;
        this.associationResolver = associationResolver;
    }

    /**
     * The AssociationValue to find the saga instance with, or {@code null} if no AssociationValue can be found on the
     * given {@code eventMessage}.
     *
     * @param eventMessage The event message containing the value of the association
     * @return the AssociationValue to find the saga instance with, or {@code null} if none found
     */
    public AssociationValue getAssociationValue(EventMessage eventMessage) {
        if (associationResolver == null) {
            return null;
        }
        Object associationValue = associationResolver.resolve(associationPropertyName, eventMessage, this);
        return new AssociationValue(associationKey, associationValue == null ? null : associationValue.toString());
    }

    /**
     * Returns the creation policy of the inspected method.
     *
     * @return the creation policy of the inspected method
     */
    public SagaCreationPolicy getCreationPolicy() {
        return creationPolicy;
    }
}

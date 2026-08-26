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
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.String.format;

/**
 * Utility class that inspects annotation on a Saga instance and returns the relevant configuration for its Event
 * Handlers.
 *
 * @author Allard Buijze
 * @author Sofia Guy Ang
 * @since 0.7
 */
public class SagaMethodMessageHandlerDefinition implements HandlerEnhancerDefinition {

    private final Map<Class<? extends AssociationResolver>, AssociationResolver> associationResolverMap;

    /**
     * Constructs a default {@link SagaMethodMessageHandlerDefinition}.
     */
    public SagaMethodMessageHandlerDefinition() {
        this.associationResolverMap = new ConcurrentHashMap<>();
    }

    @Override
    public <T> MessageHandlingMember<T> wrapHandler(MessageHandlingMember<T> original) {
        Optional<String> keyName = original.attribute("SagaEventHandler.keyName");
        Optional<String> associationProperty = original.attribute("SagaEventHandler.associationProperty");
        Optional<Class<? extends AssociationResolver>> associationResolver = original.attribute(
                "SagaEventHandler.associationResolver");
        if (keyName.isPresent() && associationProperty.isPresent() && associationResolver.isPresent()) {
            Optional<Boolean> optionalForceNew = original.attribute("StartSaga.forceNew");
            SagaCreationPolicy creationPolicy = optionalForceNew
                    .map(forceNew -> forceNew ? SagaCreationPolicy.ALWAYS : SagaCreationPolicy.IF_NONE_FOUND)
                    .orElse(SagaCreationPolicy.NONE);
            return doWrapHandler(original,
                                 creationPolicy,
                                 keyName.get(),
                                 associationProperty.get(),
                                 associationResolver.get());
        } else {
            return original;
        }
    }

    private <T> MessageHandlingMember<T> doWrapHandler(MessageHandlingMember<T> original,
                                                       SagaCreationPolicy creationPolicy,
                                                       String associationKeyName, String associationPropertyName,
                                                       Class<? extends AssociationResolver> associationResolverClass) {
        String associationKey = associationKey(associationKeyName, associationPropertyName);
        AssociationResolver associationResolver = findAssociationResolver(associationResolverClass);
        associationResolver.validate(associationPropertyName, original);
        return new SagaMethodMessageHandlingMember<>(
                original, creationPolicy, associationKey, associationPropertyName, associationResolver
        );
    }

    private String associationKey(String keyName, String associationProperty) {
        return "".equals(keyName) ? associationProperty : keyName;
    }

    private AssociationResolver findAssociationResolver(Class<? extends AssociationResolver> associationResolverClass) {
        return this.associationResolverMap.computeIfAbsent(
                associationResolverClass, this::instantiateAssociationResolver
        );
    }

    private AssociationResolver instantiateAssociationResolver(
            Class<? extends AssociationResolver> associationResolverClass
    ) {
        try {
            return associationResolverClass.getConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException |
                 InvocationTargetException e) {
            throw new AxonConfigurationException(format(
                    "`AssociationResolver` %s must define an accessible no-args constructor.",
                    associationResolverClass.getName()
            ), e);
        }
    }
}

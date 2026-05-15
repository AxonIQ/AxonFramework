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

package org.axonframework.messaging.eventhandling.annotation;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.annotation.HandlerAttributes;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.SequencingPolicy;
import org.axonframework.messaging.core.annotation.UnsupportedHandlerException;
import org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember;
import org.axonframework.messaging.eventhandling.EventMessage;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

/**
 * Definition of {@link EventHandlingMember event handlers} that are annotated with {@link SequencingPolicy}. These
 * handlers are wrapped with a {@link SequencingPolicyEventMessageHandlingMember} that provides access to the configured
 * sequencing policy.
 * <p>
 * The {@link SequencingPolicy} annotation can be applied either directly to the handler method or to the declaring
 * class. When applied to the class, all handler methods in that class will inherit the sequencing policy. Method-level
 * annotations take precedence over class-level annotations.
 *
 * @author Mateusz Nowak
 * @since 5.0.0
 */
public class MethodSequencingPolicyEventHandlerDefinition implements HandlerEnhancerDefinition {

    @Override
    public <T> MessageHandlingMember<T> wrapHandler(MessageHandlingMember<T> original) {
        // Only wrap event handlers - check the message type to work through any existing wrappers
        if (!original.canHandleMessageType(EventMessage.class)) {
            return original;
        }

        // Check for sequencing policy attributes (set by @HasHandlerAttributes on @SequencingPolicy annotation)
        @SuppressWarnings("rawtypes")
        Optional<Class<? extends org.axonframework.messaging.core.sequencing.SequencingPolicy>> policyType =
                original.attribute(HandlerAttributes.SEQUENCING_POLICY_TYPE);
        Optional<String[]> parameters = original.attribute(HandlerAttributes.SEQUENCING_POLICY_PARAMETERS);

        // Fallback to class-level annotation if method-level not found
        // @HasHandlerAttributes only scans the method, not the declaring class
        if (policyType.isEmpty()) {
            Optional<SequencingPolicy> classLevelAnnotation = original.unwrap(Method.class)
                                                                      .map(Method::getDeclaringClass)
                                                                      .map(clazz -> clazz.getAnnotation(SequencingPolicy.class));

            if (classLevelAnnotation.isPresent()) {
                policyType = Optional.of(classLevelAnnotation.get().type());
                parameters = Optional.of(classLevelAnnotation.get().parameters());
            }
        }

        if (policyType.isEmpty()) {
            return original;
        }

        return new SequencingPolicyEventMessageHandlingMember<>(
                original,
                policyType.get(),
                parameters.orElse(new String[0])
        );
    }

    /**
     * Extracting {@link org.axonframework.messaging.core.sequencing.SequencingPolicy} from the {@link SequencingPolicy}
     * annotation.
     *
     * @param <T> The type of the declaring class of the event handling method.
     */
    @Internal
    static class SequencingPolicyEventMessageHandlingMember<T>
            extends WrappedMessageHandlingMember<T> {

        private final org.axonframework.messaging.core.sequencing.SequencingPolicy<? super EventMessage> sequencingPolicy;

        /**
         * Constructs a new SequencingPolicyEventMessageHandlingMember by wrapping the given {@code original} handler
         * and creating a sequencing policy instance from the policy type and parameters.
         *
         * @param original   The original message handling member to wrap.
         * @param policyType The sequencing policy class to instantiate.
         * @param parameters The parameters to pass to the policy constructor.
         */
        @SuppressWarnings("rawtypes")
        private SequencingPolicyEventMessageHandlingMember(
                MessageHandlingMember<T> original,
                Class<? extends org.axonframework.messaging.core.sequencing.SequencingPolicy> policyType,
                String[] parameters
        ) {
            super(original);
            this.sequencingPolicy = createSequencingPolicy(policyType, parameters, original);
        }

        /**
         * Returns the sequencing policy instance created from the annotation.
         *
         * @return The sequencing policy
         */
        public org.axonframework.messaging.core.sequencing.SequencingPolicy<? super EventMessage> sequencingPolicy() {
            return sequencingPolicy;
        }

        @SuppressWarnings("rawtypes")
        private org.axonframework.messaging.core.sequencing.SequencingPolicy<? super EventMessage> createSequencingPolicy(
                Class<? extends org.axonframework.messaging.core.sequencing.SequencingPolicy> policyType,
                String[] parameters,
                MessageHandlingMember<T> original
        ) {
            try {
                return parameters.length == 0
                        ? createNoArgPolicy(policyType, original)
                        : createParameterizedPolicy(policyType, parameters, original);
            } catch (Exception e) {
                throw new UnsupportedHandlerException(
                        "Failed to create SequencingPolicy instance: " + e.getMessage(),
                        original.unwrap(Member.class).orElse(null)
                );
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private org.axonframework.messaging.core.sequencing.SequencingPolicy<? super EventMessage> createNoArgPolicy(
                Class<? extends org.axonframework.messaging.core.sequencing.SequencingPolicy> policyType,
                MessageHandlingMember<T> original
        ) throws Exception {
            try {
                var constructor = policyType.getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            } catch (NoSuchMethodException e) {
                throw new UnsupportedHandlerException(
                        "SequencingPolicy " + policyType.getName() + " must have a no-arg constructor",
                        original.unwrap(Member.class).orElse(null)
                );
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private org.axonframework.messaging.core.sequencing.SequencingPolicy<? super EventMessage> createParameterizedPolicy(
                Class<? extends org.axonframework.messaging.core.sequencing.SequencingPolicy> policyType,
                String[] parameters,
                MessageHandlingMember<T> original
        ) throws Exception {
            var matchingConstructor = findMatchingConstructor(policyType, parameters.length, original);
            var parsedParameters = parseParameters(matchingConstructor.getParameterTypes(), parameters, original);
            matchingConstructor.setAccessible(true);
            return (org.axonframework.messaging.core.sequencing.SequencingPolicy) matchingConstructor.newInstance(
                    parsedParameters);
        }

        @SuppressWarnings("rawtypes")
        private Constructor<?> findMatchingConstructor(
                Class<? extends org.axonframework.messaging.core.sequencing.SequencingPolicy> policyType,
                int parameterCount,
                MessageHandlingMember<T> original
        ) {
            return Arrays.stream(policyType.getDeclaredConstructors())
                         .filter(constructor -> countNonClassParameters(constructor) == parameterCount)
                         .findFirst()
                         .orElseThrow(() -> new UnsupportedHandlerException(
                                 "No constructor found for SequencingPolicy " + policyType.getName() +
                                         " that matches " + parameterCount
                                         + " string parameters (excluding Class parameters)",
                                 original.unwrap(Member.class).orElse(null)
                         ));
        }

        private long countNonClassParameters(Constructor<?> constructor) {
            var paramTypes = constructor.getParameterTypes();
            var hasClassAsFirstParam = paramTypes.length > 0 && paramTypes[0] == Class.class;
            return hasClassAsFirstParam ? paramTypes.length - 1 : paramTypes.length;
        }

        private Object[] parseParameters(Class<?>[] parameterTypes, String[] stringParameters,
                                         MessageHandlingMember<T> original) {
            var parsedParameters = new Object[parameterTypes.length];
            var stringParameterIndex = 0;

            for (int i = 0; i < parameterTypes.length; i++) {
                var targetType = parameterTypes[i];

                if (targetType == Class.class) {
                    if (i != 0) {
                        throw new IllegalArgumentException(
                                "Class parameter must be the first parameter in constructor. Found at position: " + i
                        );
                    }
                    parsedParameters[i] = original.payloadType();
                } else {
                    if (stringParameterIndex >= stringParameters.length) {
                        throw new IllegalArgumentException(
                                "Not enough string parameters provided. Expected parameter for type: "
                                        + targetType.getName()
                        );
                    }
                    var stringValue = stringParameters[stringParameterIndex];
                    parsedParameters[i] = parseParameter(stringValue, targetType);
                    stringParameterIndex++;
                }
            }

            return parsedParameters;
        }

        private Object parseParameter(String stringValue, Class<?> targetType) {
            return switch (targetType.getName()) {
                case "java.lang.String" -> stringValue;
                case "int", "java.lang.Integer" -> Integer.parseInt(stringValue);
                case "long", "java.lang.Long" -> Long.parseLong(stringValue);
                case "double", "java.lang.Double" -> Double.parseDouble(stringValue);
                case "float", "java.lang.Float" -> Float.parseFloat(stringValue);
                case "boolean", "java.lang.Boolean" -> Boolean.parseBoolean(stringValue);
                case "byte", "java.lang.Byte" -> Byte.parseByte(stringValue);
                case "short", "java.lang.Short" -> Short.parseShort(stringValue);
                case "char", "java.lang.Character" -> parseCharacter(stringValue);
                case "java.lang.Class" -> parseClass(stringValue);
                default -> throw new UnsupportedOperationException(
                        "Unsupported parameter type: " + targetType.getName() +
                                ". Only primitives, String, and Class are supported."
                );
            };
        }

        private char parseCharacter(String stringValue) {
            if (stringValue.length() != 1) {
                throw new IllegalArgumentException("Character parameter must be exactly one character");
            }
            return stringValue.charAt(0);
        }

        private Class<?> parseClass(String stringValue) {
            try {
                return Class.forName(stringValue);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("Cannot find class: " + stringValue, e);
            }
        }
    }
}
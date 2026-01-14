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

package org.axonframework.extension.micronaut.authorization;

import io.micronaut.security.annotation.Secured;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.lang.reflect.Executable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * MessageHandlerDefinition that verifies authorization based on
 * {@link io.micronaut.security.annotation.Secured} annotation on the message handler.
 *
 * @author Daniel Karapishchenko
 * @since 5.1.0
 */
public class SecuredMessageHandlerDefinition implements HandlerEnhancerDefinition {

    @Override
    public <T> MessageHandlingMember<T> wrapHandler(@Nonnull MessageHandlingMember<T> original) {
        return original.unwrap(Executable.class)
                       .map(executable -> AnnotationUtils.findAnnotationAttributes(executable, Secured.class)
                                                         .orElse(Map.of()))
                       .filter(attributes -> attributes.containsKey("secured"))
                       .map(attributes -> (String[]) attributes.get("secured"))
                       .map(securityConfiguration -> (MessageHandlingMember<T>) new SecuredMessageHandlingMember<>(
                               original, securityConfiguration
                       ))
                       .orElse(original);
    }

    private static class SecuredMessageHandlingMember<T> extends WrappedMessageHandlingMember<T> {

        private final Set<String> requiredRoles;

        public SecuredMessageHandlingMember(MessageHandlingMember<T> delegate,
                                            String[] securityConfiguration) {
            super(delegate);
            this.requiredRoles = new HashSet<>(Arrays.asList(securityConfiguration));
        }

        @Override
        public MessageStream<?> handle(@Nonnull Message message,
                                       @Nonnull ProcessingContext context,
                                       @Nullable T target) {
            if (!hasRequiredRoles(message)) {
                throw new UnauthorizedMessageException(
                        "Unauthorized message with identifier [" + message.identifier() + "]"
                );
            }
            return super.handle(message, context, target);
        }

        private boolean hasRequiredRoles(@Nonnull Message message) {
            Set<String> authorities = new HashSet<>();
            if (message.metadata().containsKey("authorities")) {
                authorities.addAll(Arrays.asList(message.metadata().get("authorities").split(",")));
            }
            authorities.retainAll(requiredRoles);
            return !authorities.isEmpty();
        }
    }
}

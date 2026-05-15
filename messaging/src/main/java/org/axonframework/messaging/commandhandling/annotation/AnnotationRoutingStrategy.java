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

package org.axonframework.messaging.commandhandling.annotation;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.annotation.AnnotationUtils;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.RoutingStrategy;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;

import static org.axonframework.common.ReflectionUtils.fieldsOf;
import static org.axonframework.common.ReflectionUtils.methodsOf;

/**
 * RoutingStrategy that expects an {@link Command} (meta-)annotation on the command message's payload. Commands are
 * routed based on an identifier annotated with the {@code Command}. This approach ensures that commands with the same
 * identifier, thus dealt with by the same target, are dispatched to the same node in a distributed Command Bus (e.g.
 * {@code AxonServerCommandBus} or {@code DistributedCommandBus}).
 * <p>
 * An example would be the {@code TargetAggregateIdentifier} annotation, which is meta-annotated with
 * {@code RoutingKey}. See the AnnotationCommandTargetResolver for more details on this approach.
 * <p>
 * This class requires the returned routing keys to implement a proper {@link Object#toString()} method. An inconsistent
 * {@code toString()} method may result in different members using different routing keys for the same identifier.
 *
 * @author Allard Buijze
 * @see DistributedCommandBus
 * @since 2.0.0
 */
public class AnnotationRoutingStrategy implements RoutingStrategy {

    private static final RoutingKeyResolver NO_RESOLVE = new RoutingKeyResolver(null, null);
    private static final String NULL_DEFAULT = null;

    private final Class<? extends Annotation> annotationType;
    private final Map<Class<?>, RoutingKeyResolver> resolverMap = new ConcurrentHashMap<>();

    /**
     * Instantiate an annotation {@link RoutingStrategy}. The {@code annotationType} is defaulted to {@link Command}.
     */
    public AnnotationRoutingStrategy() {
        this(Command.class);
    }

    /**
     * Instantiate an annotation {@link RoutingStrategy}. The {@code annotationType} attribute routingKey is used to
     * indicate which field to use as routing key.
     *
     * @param annotationType The annotation specifying the field to check the payload for.
     */
    public AnnotationRoutingStrategy(Class<? extends Annotation> annotationType) {
        this.annotationType = Objects.requireNonNull(annotationType, "The annotationType can not be null.");
    }

    @Override
    public String getRoutingKey(CommandMessage command) {
        try {
            Object payload = command.payload();
            return payload == null ? null : findIdentifier(payload);
        } catch (InvocationTargetException e) {
            throw new AxonConfigurationException(
                    "An exception occurred while extracting routing information form a command",
                    e
            );
        } catch (IllegalAccessException e) {
            throw new AxonConfigurationException(
                    "The current security context does not allow "
                            + "extraction of routing information from the given command.",
                    e
            );
        }
    }


    private String findIdentifier(Object payload) throws InvocationTargetException, IllegalAccessException {
        return resolverMap
                .computeIfAbsent(payload.getClass(), this::createResolver)
                .identify(payload);
    }

    private RoutingKeyResolver createResolver(Class<?> type) {
        Optional<String> routingKey = AnnotationUtils.findAnnotationAttribute(type, annotationType, "routingKey");
        return routingKey.flatMap(name -> {
            Optional<RoutingKeyResolver> matchedField = StreamSupport
                    .stream(fieldsOf(type).spliterator(), false)
                    .filter(f -> ReflectionUtils.fieldNameFromMember(f).equals(name))
                    .findFirst()
                    .map(f -> new RoutingKeyResolver(f, null));
            return matchedField.or(
                    () ->
                            StreamSupport
                                    .stream(methodsOf(type).spliterator(), false)
                                    .filter(m -> ReflectionUtils.fieldNameFromMember(m).equals(name))
                                    .findFirst()
                                    .map(ReflectionUtils::ensureAccessible)
                                    .map(m -> new RoutingKeyResolver(null, m)));
        }).orElse(NO_RESOLVE);
    }

    private record RoutingKeyResolver(Field field, Method method) {

        public String identify(Object command) throws InvocationTargetException, IllegalAccessException {
            if (method != null) {
                return Objects.toString(method.invoke(command), NULL_DEFAULT);
            } else if (field != null) {
                return Objects.toString(ReflectionUtils.getFieldValue(field, command), NULL_DEFAULT);
            }
            return null;
        }
    }
}

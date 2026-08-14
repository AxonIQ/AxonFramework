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

package org.axonframework.eventsourcing.annotation;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.CommandAppendCriteriaResolver;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventstreaming.EventCriteria;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the single static method that resolves append criteria for every command handler on its declaring class.
 * <p>
 * The method must return a non-null {@link EventCriteria}. Its first parameter is a command payload type that accepts
 * every command handled by the class, or {@link CommandMessage}. Additional parameters may be the accumulated sourcing
 * {@link EventCriteria}, {@link CommandMessage}, {@link Metadata}, a value annotated with {@link MetadataValue},
 * {@link ProcessingContext}, {@link Configuration}, or a synchronously available configured component.
 * <p>
 * The builder runs separately for every command invocation. Its result replaces the complete sourcing-derived append
 * criteria for that command's transaction.
 * <p>
 * Scope is the declaring class, and is not inherited in either direction. The builder applies to the
 * {@link org.axonframework.messaging.commandhandling.annotation.CommandHandler CommandHandlers} declared on its own
 * class only, so its command parameter needs to accept those commands and no others. A command handler inherited from
 * a supertype resolves through the builder declared by that supertype, if any. Consequently, each class in a hierarchy
 * that declares command handlers declares its own builder, and a builder on a class declaring no command handler of
 * its own is rejected as it could never be applied.
 *
 * @author Mateusz Nowak
 * @see CommandAppendCriteriaResolver
 * @since 5.4.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AppendCriteriaBuilder {
}

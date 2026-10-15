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

package org.axonframework.eventsourcing.commandhandling.annotation;

import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.commandhandling.AppendCriteriaResolver;
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
 * Marks a static method that resolves the append criteria for the commands of one type handled by its declaring class.
 * <p>
 * The method must return a non-null {@link EventCriteria}. Its first parameter is the command type it covers: a command
 * payload type, which also covers the payload's subtypes, or {@link CommandMessage}, which covers every command no
 * payload-typed builder covers. Additional parameters may be the accumulated sourcing {@link EventCriteria},
 * {@link CommandMessage}, {@link Metadata}, a {@code String} annotated with {@link MetadataValue},
 * {@link ProcessingContext}, {@link Configuration}, or a synchronously available configured component. Any other
 * parameter fails at startup.
 * <p>
 * As for {@link org.axonframework.eventsourcing.annotation.EventCriteriaBuilder}, these parameters are not resolved
 * through the {@link org.axonframework.messaging.core.annotation.ParameterResolverFactory} that resolves message
 * handler parameters, so custom parameter resolvers do not apply. A builder runs while the append condition is
 * finalized at commit, where handler parameters such as an event appender, a command dispatcher, or an injected entity
 * would append events, dispatch commands, or source events from within the commit.
 * <p>
 * The builder runs separately for every command invocation. Its result replaces the complete sourcing-derived append
 * criteria for that command's transaction.
 * <p>
 * A class may declare several builders, one per command type, like
 * {@link org.axonframework.eventsourcing.annotation.EventCriteriaBuilder} allows one per identifier type. Each
 * {@link org.axonframework.messaging.commandhandling.annotation.CommandHandler} is matched at startup with the builder
 * whose command type is the most specific supertype of the payload it handles. Two builders for the same command type
 * on one class are rejected, and so is a payload accepted by several builders of which none is more specific than the
 * others.
 * <p>
 * <b>Unlike {@code @EventCriteriaBuilder}, there is no fallback.</b> Once a class has a builder, every command handler
 * on it has to be matched by one, and startup fails otherwise. Falling back to the sourcing criteria would be silent:
 * for a command that sources nothing, a forgotten builder turns a uniqueness check into an unconditional append. To
 * keep the sourcing criteria for a command on purpose, declare a builder for it that returns them unchanged.
 * <p>
 * Builders are inherited. The builders that apply to the handlers a class declares are the ones on that class and on
 * its superclasses; for the same command type, the closest declaration wins. A base class may contribute builders
 * without declaring any command handler itself. Interfaces are not searched, as the method is required to be
 * {@code static}. Handlers a class inherits are matched against the builders of the class declaring them, so a builder
 * on a subclass does not apply to handlers inherited from its superclass. Startup fails when such a builder would be
 * the one selected for an inherited handler, such as a subclass builder for a command only its superclass handles, as
 * it would never run. Declare the builder on the superclass instead, or redeclare the handler on the subclass.
 * <p>
 * Example of a command-handling class narrowing the boundary of one command and keeping it for another:
 * <pre>
 * class AccountCommandHandler {
 *
 *     {@literal @}CommandHandler
 *     void handle(WithdrawMoney command, {@literal @}InjectEntity Account account, EventAppender appender) {
 *         // decides against the balance sourced from deposits and withdrawals
 *     }
 *
 *     {@literal @}CommandHandler
 *     void handle(DepositMoney command, {@literal @}InjectEntity Account account, EventAppender appender) {
 *         // decides against the same sourced state
 *     }
 *
 *     // A concurrent deposit only raises the balance, so only a concurrent withdrawal rejects the append.
 *     {@literal @}AppendCriteriaBuilder
 *     static EventCriteria withdrawals(WithdrawMoney command,
 *                                      EventCriteria sourcingCriteria,
 *                                      MessageTypeResolver messageTypeResolver) {
 *         return sourcingCriteria.withEventTypes(messageTypeResolver, MoneyWithdrawn.class);
 *     }
 *
 *     // Every handler needs a builder, so this one keeps the sourcing criteria on purpose.
 *     {@literal @}AppendCriteriaBuilder
 *     static EventCriteria deposits(DepositMoney command, EventCriteria sourcingCriteria) {
 *         return sourcingCriteria;
 *     }
 * }
 * </pre>
 *
 * @author Mateusz Nowak
 * @see AppendCriteriaResolver
 * @since 5.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AppendCriteriaBuilder {
}

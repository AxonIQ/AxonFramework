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

import org.axonframework.eventsourcing.commandhandling.AppendCriteriaInterceptor;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.interception.annotation.CommandHandlerInterceptor;
import org.axonframework.messaging.eventstreaming.EventCriteria;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a static method deciding the append criteria of the commands it accepts, replacing the criteria accumulated
 * through sourcing while such a command is handled.
 * <p>
 * Sourcing is unaffected: an {@link org.axonframework.eventsourcing.annotation.EventCriteriaBuilder} still decides what
 * an entity loads. An append criteria builder narrows or widens what may conflict with the decision instead, so a
 * concurrent event the decision does not depend on no longer rejects the command, or a command that loaded nothing
 * still guards a unique key.
 * <p>
 * The annotation is a {@link CommandHandlerInterceptor}, so a builder is placed and matched like any command handler
 * interceptor: on a command-handling class or an entity, applying to the commands handled there whose payload is
 * assignable to the builder's first parameter. That parameter is the command payload, which is converted to the
 * handler's payload type first, so a builder also matches a command named through
 * {@link org.axonframework.messaging.commandhandling.annotation.Command#name()}. A first parameter of type
 * {@link CommandMessage} matches every command. Commands no builder matches keep their sourcing criteria.
 * <p>
 * Unlike a regular interceptor, the method does not run before the handler. It runs when the command's events are
 * appended, once everything was sourced, and its result becomes the command's append criteria; the consistency marker
 * obtained while sourcing is kept. Hence:
 * <ul>
 *     <li>The method must be {@code static} and return a non-null {@link EventCriteria}. Being static, it does not
 *     depend on the state of the handling object, and on an entity it also covers commands creating the entity.</li>
 *     <li>A parameter of type {@code EventCriteria} receives the criteria accumulated through sourcing, across every
 *     entity the command loaded.</li>
 *     <li>Other parameters are resolved like those of any message handler, when the events are appended. Use
 *     parameters that resolve without side effects, such as the {@link CommandMessage}, a
 *     {@link org.axonframework.messaging.core.annotation.MetadataValue} or a configured component.</li>
 * </ul>
 * The rules of {@link AppendCriteriaInterceptor}, which backs this annotation, apply as well: at most one builder or
 * interceptor per command, and how a command that loaded nothing is checked.
 * <p>
 * Example narrowing the boundary of withdrawals, while deposits keep their sourcing criteria:
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
 * }
 * </pre>
 *
 * @author Mateusz Nowak
 * @see AppendCriteriaInterceptor
 * @since 5.5.0
 */
@CommandHandlerInterceptor
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
public @interface AppendCriteriaBuilder {

}

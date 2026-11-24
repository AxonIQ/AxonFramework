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

package org.axonframework.eventsourcing.annotation.reflection;

import org.axonframework.messaging.core.MessageTypeResolver;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that indicates that a method or constructor is a factory method for an event-sourced entity, forcing
 * creation at all times.
 * <p>
 * It is meta-annotated with {@link EntityCreator} to support all behavior as described on the {@code EntityCreator}.
 * What this annotation adds, is the assurance that <b>if</b> sufficient information is available to invoke the
 * annotated method or constructor, that it will be invoked regardless of downstream usages of the entity itself. This
 * adjusts the regular behavior of the no-arguments, identifier-only, and event-based {@code EntityCreator} solutions
 * like so:
 * <ol>
 *     <li>A no-arguments, {@code @EntityCreator} annotated constructor or method, will always create the entity.</li>
 *     <li>An identifier-based, {@code @EntityCreator} annotated constructor or method, will create if an identifier can be resolved.</li>
 *     <li>An event-based, {@code @EntityCreator} annotated constructor or method, will <b>only</b> create if an initial event is present.</li>
 * </ol>
 * <p>
 * This additional behavior becomes important for create-if-missing styled handlers. For plain {@code EntityCreator}
 * solutions, the create-if-missing handler should either (1) be {@code static} (read: marked as a creational handler)
 * when present in the entity itself or (2) moved out of the entity entirely. In both scenarios the create-if-missing
 * behavior would require the entity as a parameter to the message handling function validate if it does exist. If
 * optional subsequent decisions require life state changes of the potentially missing entity, the user is enforced to
 * inject a {@link org.axonframework.modelling.repository.ManagedEntity} instead.
 * <p>
 * This annotation adjusts that behavior, by simply invoking the {@code @ForcedEntityCreator} annotated constructor or
 * method and passing it through. This will effectively make the entity non-null for all no-argument scenarios and most
 * identifier-based scenarios. Furthermore, it allows the create-if-missing solution to work on instance command
 * handlers placed in the entity, resolving the need to make them {@code static}.
 * <p>
 * Forced creation is not limited to create-if-missing handlers. When the entity has no events yet, <b>every</b>
 * handler that receives the entity gets the just-constructed instance in its default state:
 * <ul>
 *     <li>Instance command handlers in the entity are loaded through
 *     {@link org.axonframework.modelling.repository.Repository#loadOrCreate(Object,
 *     org.axonframework.messaging.core.unitofwork.ProcessingContext)}. They run on the default state instead of
 *     failing with an {@link org.axonframework.modelling.repository.EntityNotFoundException}.</li>
 *     <li>{@link org.axonframework.modelling.annotation.InjectEntity} parameters of command and event handlers outside
 *     the entity receive the default instance too. A {@code @Nullable} parameter is never {@code null}, an
 *     {@link java.util.Optional} parameter is never empty, and
 *     {@link org.axonframework.modelling.repository.ManagedEntity#entity()} is never {@code null}. A handler written as
 *     "create if the entity is {@code null}, update otherwise" always takes the update branch.</li>
 *     <li>Static creational command handlers are the exception. Their commands are loaded through
 *     {@link org.axonframework.modelling.repository.Repository#load(Object,
 *     org.axonframework.messaging.core.unitofwork.ProcessingContext)}, which does not invoke the creator without
 *     events, so the entity counts as absent and the command reaches the static handler.</li>
 * </ul>
 * These handlers must check the entity's state themselves, for example:
 * <pre>{@code
 * public class GiftCard {
 *
 *     private final String cardId;
 *     private boolean issued;
 *
 *     @ForcedEntityCreator
 *     public GiftCard(@InjectEntityId String cardId) {
 *         this.cardId = cardId;
 *     }
 *
 *     @CommandHandler
 *     public void handle(RedeemGiftCard command, EventAppender appender) {
 *         if (!issued) {
 *             throw new IllegalStateException("Gift card [" + cardId + "] has not been issued");
 *         }
 *         appender.append(new GiftCardRedeemed(cardId, command.amount()));
 *     }
 * }
 * }</pre>
 * <p>
 * Forced creation only applies when the forced creator is the one selected. Among the creators that take no event,
 * the one with the most parameters is selected, so a plain identifier-based {@code EntityCreator} takes precedence
 * over a forced no-arguments creator. On an event-based creator this annotation has no effect without an event.
 * <p>
 * Note that this forced-creation-approach is viewed as an aggregate-centric solution, whereas this library aims to
 * steer away from that. Hence, any opportunity to use other mechanisms than this are encouraged.
 *
 * @author Steven van Beelen
 * @since 5.3.1
 */
@EntityCreator
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.ANNOTATION_TYPE})
public @interface ForcedEntityCreator {

    /**
     * The qualified names of the payload types that this factory method can handle. If a payload parameter is declared,
     * and this value is left at default, the payload's qualified name will be determined based on the
     * {@link MessageTypeResolver}.
     *
     * @return The qualified names of the payload types that this factory method can handle.
     */
    String[] payloadQualifiedNames() default {};
}

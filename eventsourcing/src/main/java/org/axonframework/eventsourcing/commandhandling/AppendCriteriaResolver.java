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

package org.axonframework.eventsourcing.commandhandling;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventstreaming.EventCriteria;

/**
 * Resolves the append criteria of a command, replacing the criteria accumulated through sourcing while it was handled.
 * <p>
 * A resolver runs once per command invocation, when the command's events are appended through its
 * {@link org.axonframework.eventsourcing.eventstore.EventStoreTransaction}. It receives the command and the complete
 * criteria accumulated through sourcing in that transaction, across every entity the command loaded, and its result
 * becomes the command's consistency boundary. Sourcing itself is unaffected. This is what sets it apart from an
 * {@link org.axonframework.eventsourcing.CriteriaResolver}, which resolves sourcing criteria for one entity identifier.
 * <p>
 * Resolution is synchronous, as it runs while the transaction's append condition is finalized. A resolver computes
 * criteria from what it is given and must not perform blocking work such as I/O.
 * <p>
 * Register a resolver through an {@link AppendCriteriaInterceptor}, or declare it as an
 * {@link org.axonframework.eventsourcing.commandhandling.annotation.AppendCriteriaBuilder} method.
 * <p>
 * An {@code AppendCriteriaInterceptor} registered on a
 * {@link org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule} receives the command as
 * dispatched. When it arrives serialized, for example from a distributed command bus, its payload is not converted yet,
 * so use {@link CommandMessage#payloadAs(Class, org.axonframework.conversion.Converter)} instead of
 * {@link CommandMessage#payload()}, or match on {@link CommandMessage#type()}.
 *
 * @author Mateusz Nowak
 * @since 5.5.0
 */
@FunctionalInterface
public interface AppendCriteriaResolver {

    /**
     * Resolves the append criteria for the given {@code command}.
     *
     * @param command          the command being handled
     * @param sourcingCriteria the complete criteria accumulated through sourcing for this command invocation
     * @param context          the context in which the command is handled
     * @return the complete append criteria for this command, never {@code null}
     */
    EventCriteria resolve(CommandMessage command,
                          EventCriteria sourcingCriteria,
                          ProcessingContext context);
}

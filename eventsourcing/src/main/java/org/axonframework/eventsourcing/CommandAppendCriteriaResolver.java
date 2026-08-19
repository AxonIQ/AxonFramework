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

package org.axonframework.eventsourcing;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventstreaming.EventCriteria;

/**
 * Resolves the complete append criteria for a command-handling component.
 * <p>
 * One resolver is configured for the component and applies to every command handled by it. The resolver runs
 * separately for each command invocation and receives the complete criteria accumulated through sourcing in that
 * command's transaction. Its result replaces those sourcing-derived criteria as the command's consistency boundary.
 * Resolution is synchronous.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@FunctionalInterface
public interface CommandAppendCriteriaResolver {

    /**
     * Resolves the complete append criteria for the given {@code command}.
     *
     * @param command the command being handled
     * @param context the context in which the command is handled
     * @param sourcingCriteria the complete criteria accumulated through sourcing for this command invocation
     * @return the complete append criteria for this command, never {@code null}
     */
    EventCriteria resolve(CommandMessage command,
                          ProcessingContext context,
                          EventCriteria sourcingCriteria);
}

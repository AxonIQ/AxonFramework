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

import org.axonframework.eventsourcing.CommandAppendCriteriaResolver;
import org.axonframework.eventsourcing.eventstore.AggregateBasedConsistencyMarker;
import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

import java.util.Objects;

/**
 * Overrides the append criteria of a command-handling transaction with the outcome of one
 * {@link CommandAppendCriteriaResolver}.
 * <p>
 * At most one override is installed per transaction, so a component carrying both an annotated and a declaratively
 * configured resolver fails rather than letting one silently win.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
final class CommandAppendCriteriaOverride {

    private static final ResourceKey<Boolean> APPLIED_KEY =
            ResourceKey.withLabel("commandAppendCriteriaOverrideApplied");

    private CommandAppendCriteriaOverride() {
    }

    static void apply(CommandMessage command,
                      ProcessingContext context,
                      EventStore eventStore,
                      CommandAppendCriteriaResolver resolver) {
        Boolean alreadyApplied = context.putResourceIfAbsent(APPLIED_KEY, Boolean.TRUE);
        if (alreadyApplied != null) {
            throw new IllegalStateException(
                    ("Cannot apply append criteria for command [%s]. Append criteria have already been defined for "
                            + "this command-handling transaction.")
                            .formatted(command.type().qualifiedName())
            );
        }
        eventStore.transaction(context).overrideAppendCondition(current -> {
            var commandCriteria = Objects.requireNonNull(
                    resolver.resolve(command, context, current.criteria()),
                    "The command append criteria resolver returned null."
            );
            if (AppendCondition.none().equals(current)) {
                return AppendCondition.withCriteria(commandCriteria);
            }
            if (current.consistencyMarker() instanceof AggregateBasedConsistencyMarker
                    && !current.criteria().equals(commandCriteria)) {
                throw new IllegalStateException(
                        "Command append criteria are not supported with aggregate-based consistency markers unless "
                                + "they equal the sourcing criteria."
                );
            }
            return current.replaceCriteria(commandCriteria);
        });
    }
}

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
package events.eventstoreinternals.appendcriteria.entity;
// tag::declarative-entity-append-criteria[]
import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.commandhandling.AppendCriteriaInterceptor;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.modelling.entity.EntityCommandHandlerInterceptor;
import org.axonframework.modelling.entity.EntityMetamodel;
import org.axonframework.modelling.entity.EntityMetamodelBuilder;

public class DeclarativeEntityAppendCriteria {

    // Used as EventSourcedEntityModule.declarative(String.class, CreditBalance.class)
    //                                 .messagingModel(DeclarativeEntityAppendCriteria::messagingModel)
    public static EntityMetamodel<CreditBalance> messagingModel(Configuration configuration,
                                                                EntityMetamodelBuilder<CreditBalance> builder) {
        AppendCriteriaInterceptor appendCriteria = new AppendCriteriaInterceptor(
                configuration.getComponent(EventStore.class),
                (command, context, sourcingCriteria) -> switch (command.type().qualifiedName().fullName()) {
                    case "credits.UseCredits" -> sourcingCriteria.replaceEventTypes(CreditsUsed.class);
                    case "credits.TopUpCredits" -> sourcingCriteria;
                    default -> throw new IllegalArgumentException(
                            "Unsupported command: " + command.type().qualifiedName()
                    );
                }
        );
        return builder
                .instanceCommandHandler(new QualifiedName("credits.UseCredits"),
                                        (command, balance, context) -> MessageStream.empty().cast())
                .instanceCommandHandler(new QualifiedName("credits.TopUpCredits"),
                                        (command, balance, context) -> MessageStream.empty().cast())
                .commandHandlerInterceptor(EntityCommandHandlerInterceptor.of(appendCriteria))
                .build();
    }
}
// end::declarative-entity-append-criteria[]

class CreditBalance {
}

record CreditsUsed(@EventTag(key = "accountId") String accountId, int amount) {
}

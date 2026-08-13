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
package events.eventstoreinternals.appendcriteria.declarative;

// tag::declarative-append-criteria[]
import org.axonframework.eventsourcing.commandhandling.CommandAppendCriteriaHandler;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.SimpleCommandHandlingComponent;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.conversion.MessageConverter;

public class CreditCommandConfiguration {

    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        return configurer.registerCommandHandlingModule(
                CommandHandlingModule.named("credit-commands")
                                     .commandHandlers()
                                     .commandHandlingComponent(
                                             CommandAppendCriteriaHandler.withAppendCriteria(
                                                     configuration -> SimpleCommandHandlingComponent
                                                             .create("credit-command-handler")
                                                             .subscribe(
                                                                     new QualifiedName("credits.UseCredits"),
                                                                     (command, context) -> MessageStream.empty()
                                                             )
                                                             .subscribe(
                                                                     new QualifiedName("credits.TopUpCredits"),
                                                                     (command, context) -> MessageStream.empty()
                                                             ),
                                                     configuration -> (command, context, sourcingCriteria) -> {
                                                         MessageConverter converter =
                                                                 context.component(MessageConverter.class);
                                                         return switch (command.type()
                                                                               .qualifiedName()
                                                                               .fullName()) {
                                                             case "credits.UseCredits" -> {
                                                                 command.payloadAs(UseCredits.class, converter);
                                                                 yield sourcingCriteria.intersectEventTypes(
                                                                         CreditsUsed.class.getName()
                                                                 );
                                                             }
                                                             case "credits.TopUpCredits" -> sourcingCriteria;
                                                             default -> throw new IllegalArgumentException(
                                                                     "Unsupported command: " + command.type()
                                                                                              .qualifiedName()
                                                             );
                                                         };
                                                     }
                                             )
                                     )
        );
    }
}
// end::declarative-append-criteria[]

record UseCredits(String accountId, int amount) {
}

record CreditsUsed(String accountId, int amount) {
}

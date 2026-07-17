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
package commands.commandhandlers.declarative;

// tag::declarative-configuration[]
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.SimpleCommandHandlingComponent;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageTypeResolver;

public class FacultyAnnouncementConfiguration {

    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        return configurer.registerCommandHandlingModule(
            CommandHandlingModule.named("FacultyAnnouncement")
                                 .commandHandlers()
                                 .commandHandlingComponent(config -> { // <1>
                                     MessageTypeResolver resolver = config.getComponent(MessageTypeResolver.class);
                                     return SimpleCommandHandlingComponent
                                             .create("FacultyAnnouncementComponent")
                                             .subscribe(
                                                     resolver.resolveOrThrow(SendFacultyAnnouncement.class).qualifiedName(), // <2>
                                                     (command, context) -> {
                                                         var payload = command.payloadAs(SendFacultyAnnouncement.class);
                                                         context.component(NotificationService.class) // <3>
                                                                .sendNotification(new NotificationService.Notification(
                                                                        payload.recipientId(), payload.message()));
                                                         return MessageStream.empty().cast(); // <4>
                                                     });
                                 })
        );
    }
}
// end::declarative-configuration[]

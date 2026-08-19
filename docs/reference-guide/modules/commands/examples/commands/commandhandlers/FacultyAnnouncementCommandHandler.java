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
package commands.commandhandlers;

// tag::stateless-command-handler[]
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.springframework.stereotype.Component;

@Component // <1>
public class FacultyAnnouncementCommandHandler {

    private final NotificationService notificationService; // <2>

    public FacultyAnnouncementCommandHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @CommandHandler // <3>
    public void handle(SendFacultyAnnouncement command) {
        // Access state through service, but don't maintain it in the handler
        notificationService.sendNotification(
                new NotificationService.Notification(command.recipientId(), command.message())); // <4>
    }
}
// end::stateless-command-handler[]

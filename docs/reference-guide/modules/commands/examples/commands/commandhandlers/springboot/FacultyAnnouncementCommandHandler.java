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
package commands.commandhandlers.springboot;

// tag::spring-boot-command-handler[]
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.springframework.stereotype.Component;

@Component
public class FacultyAnnouncementCommandHandler {

    private final NotificationService notificationService;

    public FacultyAnnouncementCommandHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @CommandHandler
    public void handle(SendFacultyAnnouncement command) {
        notificationService.sendNotification(
                new NotificationService.Notification(command.recipientId(), command.message()));
    }
}
// end::spring-boot-command-handler[]

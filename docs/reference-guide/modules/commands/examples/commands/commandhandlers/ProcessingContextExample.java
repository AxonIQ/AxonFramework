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

import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;

class ProcessingContextExample {

    private final NotificationService notificationService;

    ProcessingContextExample(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    // tag::handle-with-processing-context[]
    @CommandHandler
    public void handle(
        SendFacultyAnnouncement command,
        ProcessingContext context // <1>
    ) {
        notificationService.sendNotification(
                new NotificationService.Notification(command.recipientId(), command.message()));

        context.runOnCommit(ctx -> notificationService.sendNotification( // <2>
                new NotificationService.Notification("audit", "Announcement delivered to " + command.recipientId())));
    }
    // end::handle-with-processing-context[]
}

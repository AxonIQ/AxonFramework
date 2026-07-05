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

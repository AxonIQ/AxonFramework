package commands.commandhandlers.autodetected;

// tag::autodetected-command-handler[]
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

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
// end::autodetected-command-handler[]

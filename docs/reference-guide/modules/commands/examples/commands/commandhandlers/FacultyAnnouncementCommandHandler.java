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

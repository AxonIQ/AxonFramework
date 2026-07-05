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

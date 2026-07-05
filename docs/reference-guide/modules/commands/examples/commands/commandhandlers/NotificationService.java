package commands.commandhandlers;

/**
 * Supporting service used by the command handler samples on the command-handlers page.
 */
public interface NotificationService {

    void sendNotification(Notification notification);

    record Notification(String recipientId, String message) {
    }
}

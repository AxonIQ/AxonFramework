package commands.commandhandlers.springboot;

public interface NotificationService {

    void sendNotification(Notification notification);

    record Notification(String recipientId, String message) {
    }
}

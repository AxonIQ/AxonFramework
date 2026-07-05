package commands.commandhandlers.autodetected;

public interface NotificationService {

    void sendNotification(Notification notification);

    record Notification(String recipientId, String message) {
    }
}

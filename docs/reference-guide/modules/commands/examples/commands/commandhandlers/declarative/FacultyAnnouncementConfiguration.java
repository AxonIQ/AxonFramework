package commands.commandhandlers.declarative;

// tag::declarative-configuration[]
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.SimpleCommandHandlingComponent;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageTypeResolver;

public class FacultyAnnouncementConfiguration {

    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        return configurer.registerCommandHandlingModule(
            CommandHandlingModule.named("FacultyAnnouncement")
                                 .commandHandlers()
                                 .commandHandlingComponent(config -> { // <1>
                                     MessageTypeResolver resolver = config.getComponent(MessageTypeResolver.class);
                                     return SimpleCommandHandlingComponent
                                             .create("FacultyAnnouncementComponent")
                                             .subscribe(
                                                     resolver.resolveOrThrow(SendFacultyAnnouncement.class).qualifiedName(), // <2>
                                                     (command, context) -> {
                                                         var payload = command.payloadAs(SendFacultyAnnouncement.class);
                                                         context.component(NotificationService.class) // <3>
                                                                .sendNotification(new NotificationService.Notification(
                                                                        payload.recipientId(), payload.message()));
                                                         return MessageStream.empty().cast(); // <4>
                                                     });
                                 })
        );
    }
}
// end::declarative-configuration[]

package commands.commandhandlers.autodetected;

// tag::autodetected-configuration[]
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;

public class FacultyAnnouncementConfiguration {

    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        return configurer.registerCommandHandlingModule(
            CommandHandlingModule.named("FacultyAnnouncement")
                                 .commandHandlers()
                                 .autodetectedCommandHandlingComponent(config ->
                                         new FacultyAnnouncementCommandHandler(
                                                 config.getComponent(NotificationService.class)))
        );
    }
}
// end::autodetected-configuration[]

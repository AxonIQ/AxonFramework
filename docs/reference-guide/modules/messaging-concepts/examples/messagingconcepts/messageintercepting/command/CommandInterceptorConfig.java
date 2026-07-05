package messagingconcepts.messageintercepting.command;

import org.axonframework.messaging.core.configuration.MessagingConfigurer;

public class CommandInterceptorConfig {

    // tag::register-command-dispatch[]
    public void registerCommandDispatchInterceptor(MessagingConfigurer configurer) {
        configurer.registerCommandDispatchInterceptor(
                config -> new CommandLoggingDispatchInterceptor()
        );
    }
    // end::register-command-dispatch[]

    // tag::register-command-handler[]
    public void registerCommandHandlerInterceptor(MessagingConfigurer configurer) {
        configurer.registerCommandHandlerInterceptor(
                config -> new AuthorizationInterceptor()
        );
    }
    // end::register-command-handler[]
}

package commands.configuration.interceptors.handler;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// tag::handler-interceptor-spring[]
@Configuration
public class InterceptorConfig {

    @Bean
    public MessageHandlerInterceptor<? super CommandMessage> securityInterceptor() {
        return (command, context, interceptorChain) -> {
            checkPermissions(command);
            return interceptorChain.proceed(command, context);
        };
    }
    // end::handler-interceptor-spring[]

    private void checkPermissions(Object command) {
        // Verify the caller may issue this command.
    }
    // tag::handler-interceptor-spring[]
}
// end::handler-interceptor-spring[]

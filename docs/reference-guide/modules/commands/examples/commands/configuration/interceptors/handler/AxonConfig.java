package commands.configuration.interceptors.handler;

import java.lang.invoke.MethodHandles;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// tag::handler-interceptor-config-api[]
public class AxonConfig {

    private static final Logger logger =
        LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public static void main(String[] args) {
        MessagingConfigurer.create().registerCommandHandlerInterceptor(
                config -> (command, context, interceptorChain) -> {
                    checkPermissions(command);
                    return interceptorChain.proceed(command, context);
                }
        );
    }
    // end::handler-interceptor-config-api[]

    private static void checkPermissions(Object command) {
        // Verify the caller may issue this command.
    }
    // tag::handler-interceptor-config-api[]
}
// end::handler-interceptor-config-api[]

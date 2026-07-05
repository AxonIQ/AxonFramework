package messagingconcepts.messagecorrelation;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.correlation.CorrelationDataProvider;

// tag::custom-provider[]
public class AuthCorrelationDataProvider implements CorrelationDataProvider {

    private final Function<String, String> usernameProvider;

    public AuthCorrelationDataProvider(Function<String, String> userProvider) {
        this.usernameProvider = userProvider;
    }

    @Override
    public Map<String, String> correlationDataFor(Message message) {
        Map<String, String> correlationData = new HashMap<>();

        // Extract auth token from metadata
        String token = message.metadata().get("authorization");
        if (token != null) {
            String username = usernameProvider.apply(token);
            correlationData.put("username", username);
        }

        return correlationData;
    }
}
// end::custom-provider[]

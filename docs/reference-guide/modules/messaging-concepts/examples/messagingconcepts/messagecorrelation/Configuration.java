package messagingconcepts.messagecorrelation;

import org.axonframework.messaging.core.correlation.CorrelationDataProvider;
import org.axonframework.messaging.core.correlation.MessageOriginProvider;
import org.axonframework.messaging.core.correlation.MultiCorrelationDataProvider;
import org.axonframework.messaging.core.correlation.SimpleCorrelationDataProvider;

// tag::multi-provider[]
import java.util.List;public class Configuration {

    public CorrelationDataProvider customCorrelationDataProviders() {
        return new MultiCorrelationDataProvider(
            List.of(
                new MessageOriginProvider(),
                new SimpleCorrelationDataProvider("tenantId", "userId")
            )
        );
    }
}
// end::multi-provider[]

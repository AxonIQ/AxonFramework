package messagingconcepts.messagecorrelation;

import org.axonframework.messaging.core.correlation.CorrelationDataProvider;
import org.axonframework.messaging.core.correlation.SimpleCorrelationDataProvider;

// tag::simple-provider[]
public class AxonConfig {

    public CorrelationDataProvider simpleProvider() {
        return new SimpleCorrelationDataProvider("tenantId", "userId");
    }
}
// end::simple-provider[]

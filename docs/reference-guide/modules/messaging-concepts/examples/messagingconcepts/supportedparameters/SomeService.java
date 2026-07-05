package messagingconcepts.supportedparameters;

import org.axonframework.messaging.core.Context.ResourceKey;

public class SomeService {

    public static final ResourceKey<SomeService> RESOURCE_KEY = ResourceKey.withLabel("someService");
}

/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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

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
package configuration.spring;

// tag::named-component-registration[]
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
class ReplayTokenStoreEnhancer implements ConfigurationEnhancer {

    @Override
    public void enhance(ComponentRegistry registry) {
        registry.registerComponent(TokenStore.class, "replayTokenStore", config -> new InMemoryTokenStore());
    }
}
// end::named-component-registration[]

// tag::named-component-injection[]
@Component
class TokenStoreMaintenance {

    private final TokenStore tokenStore;
    private final TokenStore replayTokenStore;

    TokenStoreMaintenance(TokenStore tokenStore, // <1>
                          @Qualifier("replayTokenStore") TokenStore replayTokenStore) { // <2>
        this.tokenStore = tokenStore;
        this.replayTokenStore = replayTokenStore;
    }
}
// end::named-component-injection[]

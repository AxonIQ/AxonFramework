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
package configuration.configurer.component;

// tag::type-reference-component-example[]
import org.axonframework.common.TypeReference;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;

class TypeReferenceComponentExample {

    void register(ComponentRegistry registry) {
        registry.registerComponent(
                ComponentDefinition.ofType(new TypeReference<EntityCache<MyId, MyEntity>>() {})
                                   .withBuilder(config -> id -> null)
        );
    }

    void retrieve(Configuration configuration) {
        EntityCache<MyId, MyEntity> cache =
                configuration.getComponent(new TypeReference<EntityCache<MyId, MyEntity>>() {});
    }
}
// end::type-reference-component-example[]

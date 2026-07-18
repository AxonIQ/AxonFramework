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
package commands.entities.entitypolymorphism.autodetected;

// The page displays the imports and the registration method as one snippet. The method is
// deliberately left at column 0 inside the wrapper class so both tag regions share the same
// indentation and the rendered snippet matches the page byte for byte.
// tag::autodetected-registration[]
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

// end::autodetected-registration[]
class CourseEntityRegistration {

// tag::autodetected-registration[]
static void registerCourseEntity(EventSourcingConfigurer configurer) {
    configurer.registerEntity(
        EventSourcedEntityModule.autodetected(String.class, CourseEntity.class) // <1>
    );
}
// end::autodetected-registration[]
}

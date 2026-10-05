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

package org.axonframework.extension.springboot.polymorphicentity;

/**
 * Concrete subtype of {@link AbstractCourse}. Deliberately carries no
 * {@link org.axonframework.extension.spring.stereotype.EventSourced} annotation of its own, matching the documented
 * Spring Boot polymorphism pattern where only the abstract parent is annotated.
 */
public class OnlineCourse extends AbstractCourse {

}

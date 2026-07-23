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

package org.axonframework.examples.demo.multitenancy.university.write.course;

/**
 * Command opening a course with a fixed number of seats. Like every message in the demo, the tenant it
 * belongs to travels in the command's metadata rather than in the payload, so the framework resolves the
 * tenant and routes the resulting events to that tenant's event store.
 *
 * @param courseId the identifier of the course to open
 * @param capacity the number of seats the course offers
 */
public record OpenCourse(String courseId, int capacity) {

}

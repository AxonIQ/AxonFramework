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

package org.axonframework.examples.demo.multitenancy.university.write.enrollstudent;

/**
 * Command enrolling a student in a course. The tenant it belongs to travels in the command's metadata
 * rather than in the payload, so the framework resolves the tenant and the payload stays tenant-agnostic.
 * <p>
 * Handling it exercises both multi-tenancy features at once: the event-sourced course it sources and
 * appends to lives in the tenant's own event store, and the tenant's read-model components it updates are
 * injected for that same tenant.
 *
 * @param courseId  the identifier of the course to enroll in
 * @param studentId the identifier of the enrolling student
 */
public record EnrollStudent(String courseId, String studentId) {

}

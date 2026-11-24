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

package commands.entities.entityidresolution;

import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.modelling.EntityIdResolutionException;
import org.axonframework.modelling.EntityIdResolver;

// tag::custom-resolver[]
public class FacultyCourseIdResolver implements EntityIdResolver<String> {

    @Override
    public String resolve(Message message, ProcessingContext context) throws EntityIdResolutionException {
        if (!(message.payload() instanceof EnrollInCourse command)) { // <1>
            throw new EntityIdResolutionException(
                    "Unable to resolve a course identifier for message [" + message.type() + "]."
            ); // <2>
        }
        return command.facultyId() + "/" + command.courseId(); // <3>
    }

    public record EnrollInCourse(String facultyId, String courseId, String studentId) {

    }
}
// end::custom-resolver[]

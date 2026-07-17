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
package migration.paths.interceptors.exceptionhandler;

import org.axonframework.messaging.core.interception.annotation.ExceptionHandler;

// tag::exception-handler-migration[]
public class OrderCommandHandler {

    @ExceptionHandler(resultType = IllegalStateException.class)
    public void handleIllegalState(IllegalStateException exception) {
        // Handle exception for all commands on this component
    }

    // Narrow to a specific exception type
    @ExceptionHandler(resultType = ValidationException.class)
    public void handleValidation(ValidationException exception) {
        // Only handles ValidationException (and its subtypes)
    }
}
// end::exception-handler-migration[]

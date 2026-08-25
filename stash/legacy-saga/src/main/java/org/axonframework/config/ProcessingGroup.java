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

package org.axonframework.config;

import java.lang.annotation.*;

/**
 * Hint for the Configuration API that the annotated Event Handler object should be assigned to an Event Processor with
 * the specified name.
 * <p>
 * Explicitly provided assignment rules set in the configuration will overrule hints provided by this annotation.
 */
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Deprecated(since = "5.0.0", forRemoval = true)
public @interface ProcessingGroup {

    /**
     * The name of the Event Processor to assign the annotated Event Handler object to.
     *
     * @return the name of the Event Processor to assign objects of this type to
     */
    String value();
}

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

package org.axonframework.extension.micronaut.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation intended for types and methods to trigger the registration of entities found in the given
 * {@link #packages()} with the {@link DefaultEntityRegistrar}.
 *
 * @author Allard Buijze
 * @since 3.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(DefaultEntityRegistrar.class)
public @interface RegisterDefaultEntities {

    /**
     * An array of package names used by the {@link DefaultEntityRegistrar} to collect entities from.
     *
     * @return An array of package names used by the {@link DefaultEntityRegistrar} to collect entities from.
     */
    String[] packages();
}

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

package org.axonframework.modelling.annotation;

import org.axonframework.common.annotation.Internal;

/**
 * The default {@link EntityIdResolverDefinition} to be used as an attribute on annotation to clarify a default is being
 * used.
 * <p>
 * This default {@code EntityIdResolverDefinition} extends {@link AnnotationBasedEntityIdResolverDefinition}, as that's
 * the desired default {@code EntityIdResolverDefinition} throughout.
 *
 * @author Steven van Beelen
 * @see AnnotationBasedEntityIdResolverDefinition
 * @since 5.4.0
 */
@Internal
public class DefaultEntityIdResolverDefinition extends AnnotationBasedEntityIdResolverDefinition {

}

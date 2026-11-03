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

import org.axonframework.common.configuration.Configuration;
import org.axonframework.conversion.Converter;
import org.axonframework.modelling.EntityIdResolver;
import org.axonframework.modelling.FallbackEntityIdResolver;
import org.axonframework.modelling.MetadataEntityIdResolver;
import org.axonframework.modelling.annotation.AnnotationBasedEntityIdResolver;
import org.axonframework.modelling.annotation.EntityIdResolverDefinition;
import org.axonframework.modelling.entity.EntityMetamodel;

// tag::definition[]
public class MetadataFallbackEntityIdResolverDefinition implements EntityIdResolverDefinition {

    @Override
    public <E, ID> EntityIdResolver<ID> createIdResolver(Class<E> entityType,
                                                         Class<ID> idType,
                                                         EntityMetamodel<E> entityMetamodel,
                                                         Configuration configuration) {
        return new FallbackEntityIdResolver<>( // <1>
                new AnnotationBasedEntityIdResolver<>(),
                MetadataEntityIdResolver.forKey("entityId", idType, configuration.getComponent(Converter.class))
        );
    }
}
// end::definition[]

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

package org.axonframework.modelling.configuration;

import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.ModuleBuilder;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.core.Message;
import org.axonframework.modelling.EntityIdResolver;
import org.axonframework.modelling.entity.EntityMetamodel;
import org.axonframework.modelling.entity.EntityMetamodelBuilder;
import org.axonframework.modelling.repository.SimpleRepositoryEntityLoader;
import org.axonframework.modelling.repository.SimpleRepositoryEntityPersister;
import org.axonframework.modelling.repository.Repository;

import java.util.Objects;
import java.util.function.Function;

/**
 * An expansion on the {@link EntityModule}, specifically for state-based entities.
 * <p>
 * Invoke the {@code static} {@link #declarative(Class, Class)} operation to start the builder flow for a state-based
 * entity.
 * <p>
 * Provides operations to either (1) register a {@link RepositoryPhase#loader(ComponentBuilder) entity loader} and
 * {@link PersisterPhase#persister(ComponentBuilder) entity persister}, or (2) a
 * {@link RepositoryPhase#repository(ComponentBuilder) repository} performing both the loading and persisting task.
 *
 * @param <ID> The type of identifier used to identify the state-based entity that's being built.
 * @param <E>  The type of the state-based entity being built.
 * @author Allard Buijze
 * @author Mateusz Nowak
 * @author Mitchell Herrijgers
 * @author Steven van Beelen
 * @since 5.0.0
 */
public interface StateBasedEntityModule<ID, E> extends EntityModule<ID, E> {

    /**
     * Starts the builder for a state-based entity with the given {@code entityType} and {@code idType}.
     *
     * @param idType     The type of identifier used to identify the state-based entity that's being built.
     * @param entityType The type of the state-based entity being built.
     * @param <ID>       The type of identifier used to identify the state-based entity that's being built.
     * @param <E>        The type of the state-based entity being built.
     * @return The repository phase of this builder, for a fluent API.
     */
    static <ID, E> RepositoryPhase<ID, E> declarative(Class<ID> idType,
                                                      Class<E> entityType) {
        return new SimpleStateBasedEntityModule<>(idType, entityType);
    }

    /**
     * The repository phase of the state-based entity builder.
     * <p>
     * Allows for two paths when building a state-based entity. Firstly, a
     * {@link #loader(ComponentBuilder) entity loader} can be defined, after which the builder will enforce registration
     * of an {@link PersisterPhase#persister(ComponentBuilder) entity persister}. The second option allows for providing
     * a {@link #repository(ComponentBuilder) repository} right away.
     *
     * @param <ID> The type of identifier used to identify the state-based entity that's being built.
     * @param <E>  The type of the state-based entity being built.
     */
    interface RepositoryPhase<ID, E> {

        /**
         * Registers the given {@code loader} as a factory method for the state-based entity being built.
         *
         * @param loader A factory method constructing a {@link SimpleRepositoryEntityLoader}.
         * @return The "persister" phase of this builder, for a fluent API.
         */
        PersisterPhase<ID, E> loader(ComponentBuilder<SimpleRepositoryEntityLoader<ID, E>> loader);

        /**
         * Registers the given {@code loader} for the state-based entity being built.
         * <p>
         * Convenience overload for callers that do not need the {@link org.axonframework.common.configuration.Configuration}
         * to construct the loader.
         *
         * @param loader the {@link SimpleRepositoryEntityLoader} for the state-based entity
         * @return the "persister" phase of this builder, for a fluent API
         */
        default PersisterPhase<ID, E> loader(SimpleRepositoryEntityLoader<ID, E> loader) {
            Objects.requireNonNull(loader, "The loader cannot be null.");
            return loader(configuration -> loader);
        }

        /**
         * Registers the given {@code repository} as a factory method for the state-based entity being built.
         *
         * @param repository A factory method constructing a {@link Repository}.
         * @return The parent {@link StateBasedEntityModule}, signaling the end of configuring a state-based entity.
         */
        MessagingMetamodelPhase<ID, E> repository(ComponentBuilder<Repository<ID, E>> repository);

        /**
         * Registers the given {@code repository} for the state-based entity being built.
         * <p>
         * Convenience overload for callers that do not need the {@link org.axonframework.common.configuration.Configuration}
         * to construct the repository.
         *
         * @param repository the {@link Repository} for the state-based entity
         * @return the messaging metamodel phase of this builder, for a fluent API
         */
        default MessagingMetamodelPhase<ID, E> repository(Repository<ID, E> repository) {
            Objects.requireNonNull(repository, "The repository cannot be null.");
            return repository(configuration -> repository);
        }
    }

    /**
     * The "persister" phase of the state-based entity builder.
     * <p>
     * Enforce providing the {@link #persister(ComponentBuilder)} for the state-based entity that's being built.
     *
     * @param <ID> The type of identifier used to identify the state-based entity that's being built.
     * @param <E>  The type of the state-based entity being built.
     */
    interface PersisterPhase<ID, E> {

        /**
         * Registers the given {@code persister} as a factory method for the state-based entity being built.
         *
         * @param persister A factory method constructing a {@link SimpleRepositoryEntityPersister}.
         * @return The parent {@link StateBasedEntityModule}, signaling the end of configuring a state-based entity.
         */
        MessagingMetamodelPhase<ID, E> persister(
                ComponentBuilder<SimpleRepositoryEntityPersister<ID, E>> persister
        );

        /**
         * Registers the given {@code persister} for the state-based entity being built.
         * <p>
         * Convenience overload for callers that do not need the {@link org.axonframework.common.configuration.Configuration}
         * to construct the persister.
         *
         * @param persister the {@link SimpleRepositoryEntityPersister} for the state-based entity
         * @return the messaging metamodel phase of this builder, for a fluent API
         */
        default MessagingMetamodelPhase<ID, E> persister(SimpleRepositoryEntityPersister<ID, E> persister) {
            Objects.requireNonNull(persister, "The persister cannot be null.");
            return persister(configuration -> persister);
        }
    }

    /**
     * The "messaging metamodel" phase of the state-based entity builder.
     * <p>
     * Users can provide a {@link #messagingModel(EntityMetamodelConfigurationBuilder) messaging metamodel} so the
     * entity can handle messages, such as commands and events, based on the entity's metamodel. This is optional, and
     * users can choose to skip this by calling {@link ModuleBuilder#build()}.
     *
     * @param <ID> The type of identifier used to identify the state-based entity that's being built.
     * @param <E>  The type of the state-based entity being built.
     */
    interface MessagingMetamodelPhase<ID, E> extends ModuleBuilder<StateBasedEntityModule<ID, E>> {

        /**
         * Registers the given {@code metamodelFactory} to build the entity's metamodel.
         * <p>
         * This method allows for configuring the entity's metamodel, which is used to handle messages such as commands
         * and events. This is optional, and users can choose to skip this by calling {@link ModuleBuilder#build()}.
         *
         * @param metamodelFactory A factory method constructing an {@link EntityMetamodelConfigurationBuilder}.
         * @return The next phase of this builder, allowing for configuring the entity's ID resolver.
         */
        EntityIdResolverPhase<ID, E> messagingModel(
                EntityMetamodelConfigurationBuilder<E> metamodelFactory
        );

        /**
         * Registers a messaging metamodel factory that does not require the
         * {@link org.axonframework.common.configuration.Configuration}.
         * <p>
         * Convenience overload for callers that only need the {@link EntityMetamodelBuilder}.
         *
         * @param metamodelFactory a factory constructing the {@link EntityMetamodel} from the
         *                         {@link EntityMetamodelBuilder}
         * @return the next phase of this builder, allowing for configuring the entity's ID resolver
         */
        default EntityIdResolverPhase<ID, E> messagingModel(
                Function<EntityMetamodelBuilder<E>, EntityMetamodel<E>> metamodelFactory
        ) {
            Objects.requireNonNull(metamodelFactory, "The metamodelFactory cannot be null.");
            return messagingModel((configuration, builder) -> metamodelFactory.apply(builder));
        }
    }

    /**
     * The "entity ID resolver" phase of the state-based entity builder.
     * <p>
     * Allows for providing an {@link EntityIdResolver} to resolve the entity's ID based on the command payload.
     * Required for any command handlers in the {@link org.axonframework.modelling.entity.EntityMetamodel}. This is
     * optional, and users can choose to skip this by calling {@link ModuleBuilder#build()}. Any command handlers will
     * not be subscribed to the {@link CommandBus} in that case.
     *
     * @param <ID> The type of identifier used to identify the state-based entity that's being built.
     * @param <E>  The type of the state-based entity being built.
     */
    interface EntityIdResolverPhase<ID, E> extends ModuleBuilder<StateBasedEntityModule<ID, E>> {

        /**
         * Registers the given {@code entityIdResolver} to resolver the entity's identifiers from a given
         * {@link Message} when loading.
         *
         * @param entityIdResolver A factory method constructing an {@link EntityIdResolver}.
         * @return The {@link StateBasedEntityModule}, signaling the end of this builder.
         */
        StateBasedEntityModule<ID, E> entityIdResolver(
                ComponentBuilder<EntityIdResolver<ID>> entityIdResolver
        );

        /**
         * Registers the given {@code entityIdResolver} for resolving entity identifiers from a given {@link Message}.
         * <p>
         * Convenience overload for callers that do not need the {@link org.axonframework.common.configuration.Configuration}
         * to construct the resolver.
         *
         * @param entityIdResolver the {@link EntityIdResolver} for the state-based entity
         * @return the {@link StateBasedEntityModule}, signaling the end of this builder
         */
        default StateBasedEntityModule<ID, E> entityIdResolver(EntityIdResolver<ID> entityIdResolver) {
            Objects.requireNonNull(entityIdResolver, "The entity ID resolver cannot be null.");
            return entityIdResolver(configuration -> entityIdResolver);
        }
    }
}

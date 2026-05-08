package org.axonframework.examples.demo.university.entity.eventsourced;

import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.annotation.AnnotationBasedEventCriteriaResolver;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.snapshot.api.SnapshotPolicy;
import org.axonframework.eventsourcing.snapshot.inmemory.InMemorySnapshotStore;
import org.axonframework.eventsourcing.snapshot.store.SnapshotStore;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.modelling.annotation.AnnotationBasedEntityIdResolver;
import org.axonframework.modelling.entity.annotation.AnnotatedEntityMetamodel;

/**
 * Configures {@link CourseEntity} using the declarative builder so that a snapshot policy can be applied.
 * <p>
 * The annotation-based {@code autodetected} helper does not expose snapshot configuration.
 * When snapshots are needed, use {@code declarative} and provide the metamodel, factory, criteria resolver,
 * and optional entity-id resolver explicitly.
 */
public class CourseEntitySnapshotConfiguration {

    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        configurer.componentRegistry(r -> r.registerComponent(SnapshotStore.class, c -> new InMemorySnapshotStore()));

        return configurer.registerEntity(
                EventSourcedEntityModule.declarative(String.class, CourseEntity.class)
                        .messagingModel((config, builder) -> AnnotatedEntityMetamodel.forConcreteType(
                                CourseEntity.class,
                                config.getComponent(ParameterResolverFactory.class),
                                config.getComponent(MessageTypeResolver.class),
                                config.getComponent(MessageConverter.class),
                                config.getComponent(EventConverter.class)
                        ))
                        .entityFactory(c -> EventSourcedEntityFactory.fromNoArgument(CourseEntity::new))
                        .criteriaResolver(c -> new AnnotationBasedEventCriteriaResolver<>(
                                CourseEntity.class, String.class, c
                        ))
                        .entityIdResolver(c -> new AnnotationBasedEntityIdResolver<>())
                        .snapshotPolicy(c -> SnapshotPolicy.afterEvents(3))
                        .build()
        );
    }

    private CourseEntitySnapshotConfiguration() {
        // Prevent instantiation
    }
}

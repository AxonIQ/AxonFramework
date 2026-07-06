package migration.paths.aggregates.polymorphismmigration;

// tag::declarative-polymorphic-registration[]
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.entity.PolymorphicEntityMetamodel;
import org.axonframework.modelling.entity.annotation.AnnotatedEntityMetamodel;

public class AxonConfig {
    // ...
    public void configure(EventSourcingConfigurer configurer) {
         configurer.registerEntity(
                EventSourcedEntityModule.declarative(String.class, GiftCard.class)
                        .messagingModel((configuration, builder) -> PolymorphicEntityMetamodel.forSuperType(GiftCard.class)
                                .addConcreteType(AnnotatedEntityMetamodel.forConcreteType(
                                        OpenLoopGiftCard.class,
                                        configuration.getComponent(ParameterResolverFactory.class),
                                        configuration.getComponent(MessageTypeResolver.class),
                                        configuration.getComponent(MessageConverter.class),
                                        configuration.getComponent(EventConverter.class)
                                ))
                                .addConcreteType(AnnotatedEntityMetamodel.forConcreteType(
                                        RechargeableGiftCard.class,
                                        configuration.getComponent(ParameterResolverFactory.class),
                                        configuration.getComponent(MessageTypeResolver.class),
                                        configuration.getComponent(MessageConverter.class),
                                        configuration.getComponent(EventConverter.class)
                                ))
                                .build())
                        .entityFactory(c -> EventSourcedEntityFactory.fromNoArgument(OpenLoopGiftCard::new))
                        .criteriaResolver(c -> (id, ctx) -> EventCriteria.havingTags(Tag.of("cardId", id)))
                        .build()
        );
    }
}
// end::declarative-polymorphic-registration[]

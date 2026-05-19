# Axon Framework 5 — API Changes: Entities (Aggregates) and Test Fixtures

> Part of the Axon Framework 4→5 migration guide.
> Covers: renaming of "aggregate" to "entity", declarative `EntityMetamodel`, immutable entities (Java records /
> Kotlin data classes), `@EntityCreator` constructor pattern (no more no-arg constructor requirement),
> creational vs. instance command handlers, reflection-based entity enhancements, exception mapping,
> Spring `@EventSourced` annotation; and the new `AxonTestFixture` replacing `AggregateTestFixture`
> and `SagaTestFixture`.

## Aggregates to Entities

Axon Framework 5 elevates the concept of Entities to the top level, as aggregate no longer accurately
describes the concept. With the introduction of [DCB](05-event-store-and-processors.md#event-store), more fluid boundaries of entities are possible.

This section has been written in a way that is easy to follow if you read the sections in order. However, if you
are already familiar with the changes, you can jump to the relevant section using the links below:

- [Aggregates are now referred to as Entities](#aggregates-are-now-entities).
- [Entities can now be defined declaratively, instead of only through reflection.](#declarative-modeling-first).
- [Entities can be immutable, allowing for Java records and Kotlin data classes.](#immutable-entities).
- [Entity constructors can take in the first event as a payload or `EventMessage`, allowing for non-nullable
  fields.](#entity-constructor-changes)
- [Constructor command handlers are gone, and a creational command is a static method on the entity class.](#creational-command-handlers)
- [Reflection-based entities have gained some new capabilities](#reflection-based-entities)

### Aggregates are now Entities

In Axon Framework 5, the concept of aggregates has been replaced with entities. This change reflects the shift from
a strict aggregate boundary to a more flexible entity boundary, allowing for a more fluid definition of entities
that can span multiple event streams. The term "aggregate" is no longer used in the API, and all references to
aggregates have been replaced with "entities."

### Declarative modeling first

When handling messaging for an entity, the framework needs to know which commands and events can be handled
by the entity and which child entities it has. This is what we call the 'EntityMetamodel.'

While aggregates worked only through reflection before, with the Axon Framework 5' entities this can be declaratively
defined.
You can start defining a metamodel by calling `EntityMetamodel.forEntityType(entityType)` and declare command
handlers, event handlers, and
child entities. If you have a polymorphic entity, one that has multiple concrete types and extends one supertype,
you can use `EntityMetamodel.forPolymorphicEntityType(entityType)` to define the entity metamodel.

```java
EntityMetamodel<ImmutableTask> metamodel = EntityMetamodel
        .forEntityType(ImmutableTask.class)
        .entityEvolver(new AnnotationBasedEntityEvolvingComponent<>(ImmutableTask.class))
        .instanceCommandHandler(commandQualifiedName, (command, entity, context) -> {
            // Handle the command
            return MessageStream.empty().cast();
        })
        .addChild(/* child entity definition */)
        .build();
```

However, the use of reflection is still possible. The `AnnotatedEntityMetamodel` reads the entity information
in a way that is similar to Axon Framework 4, and creates a delegate `EntityMetamodel` of the right type, with
the right handlers. This means that the entity structure is clearly defined and debuggable,
and less reflection is needed at runtime, which improves performance.

```java
EntityMetamodel<ImmutableTask> metamodel = AnnotatedEntityMetamodel.forConcreteType(
        ImmutableTask.class,
        configuration.getComponent(ParameterResolverFactory.class),
        configuration.getComponent(MessageTypeResolver.class),
        configuration.getComponent(MessageConverter.class),
        configuration.getComponent(EventConverter.class)
);
```

### Immutable entities

Event-sourced entities can now be created in an immutable fashion, which wasn't possible before Axon Framework 5.
This allows you to create entities out of Java records or Kotlin data classes:

```java
record MyEntity(
        String id,
        String name
) {

    @EventSourcingHandler
    public MyEntity on(MyEntityNameChangedEvent event) {
        return new MyEntity(id, event.getNewName());
    }
}
```

Or, in Kotlin:

```kotlin
data class MyEntity(
    val id: String,
    val name: String
) {
    @EventSourcingHandler
    fun on(event: MyEntityNameChangedEvent): MyEntity {
        return copy(name = event.newName)
    }
}
```

By returning a new instance of the entity in the event sourcing handler, you can evolve the state of the entity
without mutating the original instance. This is particularly useful in functional programming paradigms and allows for
better immutability guarantees in your code. This works with both Java records and Kotlin data classes, as well as
traditional classes.

This is made possible because the first command is handled by a static method, not a constructor, and is responsible for
verifying the command and creating the entity. These static methods
are [creational command handler](#creational-command-handlers). Once the first event is published, the entity is
created using the constructor defining the payload or `EventMessage`. Commands after this will be handled by methods on
the instance of the entity.

To evolve, or change the state, of an entity, `@EventSourcingHandlers` or `EntityEvolvers` can return a new instance of
the entity based on an event. This entity will then be used for the next command or next event.

### Entity Constructor changes

The world is moving to non-nullability guarantees, and for good reason. However, aggregates required a no-arg
constructor to be able to instantiate the aggregate. This meant that fields could not be non-nullable, as the
constructor would not be able to set them. In Axon Framework 5, this has changed.

This is how a kotlin class would traditionally look:

```kotlin
class MyPreFiveClass {
    // Kotlin classes have inherently a no-arg constructor

    @AggregateIdentifier
    private lateinit var id: String

    @CommandHandler
    fun handle(command: CreateMyEntityCommand) {
        AggregateLifecycle.apply(MyEntityCreatedEvent(command.id, command.name))
        // Other initialization logic...
    }

    @EventSourcingHandler
    fun on(event: MyEntityCreatedEvent) {
        this.id = event.id
        // Other initialization logic...
    }
}
```

As you can see, the `lateinit var` makes the `id` field non-nullable, but it can throw if not set when accessed.
In addition, you can never make it a `val`, so it remains mutable.
Java had similar limitations, but it was simply not as visible as it is in Kotlin:

```java
public class MyPreFiveClass {

    private MyPreFiveClass() {
        // No-arg constructor required for Axon Framework 4
    }

    @AggregateIdentifier
    private String id;

    @CommandHandler
    public void handle(CreateMyEntityCommand command, EventAppender appender) {
        appender.append(new MyEntityCreatedEvent(command.getId(), command.getName()));
        // Other initialization logic...
    }

    @EventSourcingHandler
    public void on(MyEntityCreatedEvent event) {
        // this.id is null here
        this.id = event.getId();
        // Other initialization logic...
    }
}
```

From Axon Framework 5 onwards, the constructor of an entity can take in the first event as a payload or `EventMessage`.
This allows you to set the fields of the entity in a non-nullable way,
and it allows you to make them `val` in Kotlin or `final` in Java.
This is what the code would look like in Kotlin:

```kotlin
data class MyEntity(
    val id: String,
    val name: String
) {
    @EntityCreator
    constructor(event: MyEntityCreatedEvent) : this(
        id = event.id,
        name = event.name
    )

    companion object {
        @CommandHandler
        fun create(command: CreateMyEntityCommand, appender: EventAppender) {
            EventAppender.append(MyEntityCreatedEvent(command.id, command.name))
        }
    }
}
```

And this is what it would look like in Java:

```java
public class MyEntity {

    @AggregateIdentifier
    private final String id;
    private final String name;

    @EntityCreator
    public MyEntity(MyEntityCreatedEvent event) {
        this.id = event.getId();
        this.name = event.getName();
    }

    @CommandHandler
    public static void create(CreateMyEntityCommand command) {
        apply(new MyEntityCreatedEvent(command.getId(), command.getName()));
    }
}
```

The way Event-Sourced entities are constructed is defined by the `EventSourcedEntityFactory` that is passed into the
`EventSourcingRepository`. There are four possible ways to construct an entity:

1. **No-arg constructor**: This is the default behavior, where the entity is constructed using a no-arg constructor. Use
   `EventSourcedEntityFactory.fromNoArgument(...)` to use this.
2. **Identifier constructor**: The entity is constructed using a constructor that takes the identifier as a payload. Use
   `EventSourcedEntityFactory.fromIdentifier(...)` to use this.
3. **Event Message**: The entity is constructed using a constructor that takes the first event message as a payload. Use
   `EventSourcedEntityFactory.fromEventMessage(...)` to use this.
4. **Reflection**: Use the `AnnotationBasedEventSourcedEntityFactory` to construct the entity using reflection, marking
   constructors (or static methods) with the `@EntityCreator` annotation. This is the default behavior in Axon
   Framework.

### Creational Command Handlers

Axon Framework 5 distinguishes two types of command handlers:

1. **Creational Command Handlers**: These are static methods on the entity class that are responsible for creating the
   entity and creating the entity, for example, by publishing the first event.
2. **Instance Command Handlers**: These are instance methods on the entity class that handle commands after the entity
   has been created.

The `EntityModel` has the `handleCreate` and `handleInstance` methods to handle these two different kind of commands,
with the `EntityModelBuilder` providing the means to define these handlers. The same command can be registered as both
creational and instance command handler, allowing you to handle the command in a static method and an instance method
depending on whether the entity is already created or not.

Here is an example of both a creational and an instance command handler in Java:

```java
public class MyEntity {

    private String id;

    @EntityCreator
    public MyEntity(MyEntityCreatedEvent event) {
        this.id = event.getId();
        // Other initialization logic...
    }

    // Creational command handler
    @CommandHandler
    public static void create(CreateMyEntityCommand command, EventAppender appender) {
        appender.append(new MyEntityCreatedEvent(command.getId(), command.getName()));
    }

    // Instance command handler
    @CommandHandler
    public void handle(UpdateMyEntityCommand command, EventAppender appender) {
        appender.append(new MyEntityUpdatedEvent(id, command.getNewName()));
        // Other update logic...
    }
}
```

### Reflection-based entities

While very similar to the reflection-based aggregates from AF4, reflection-based entities have gained some new
capabilities.

First, it is now possible to define two or more children of the same type.
Note that the `@EntityMember#commandTargetResolver` must resolve to only one value over all children.

```java
public abstract class Project {

    @EntityMember
    private List<Developer> otherDevelopers = new ArrayList<>();

    @EntityMember
    private List<Milestone> features = new ArrayList<>();
}
```

Second, the `@EntityMember#commandTargetResolver` can now be customized.
By creating your own definition, you can route the command target using something else than the `@RoutingKey`.

```java
public class Project {

    @EntityMember(commandTargetResolver = AwesomeCommandTargetDefinition.class)
    private List<Milestone> features = new ArrayList<>();

    private static class AwesomeCommandTargetDefinition implements CommandTargetResolverDefinition {

        @Nonnull
        @Override
        public <E> CommandTargetResolver<E> createCommandTargetResolver(@Nonnull AnnotatedEntityModel<E> entity,
                                                                        @Nonnull Member member) {
            return (candidates, message, context) -> {
                return candidates.stream().filter(d -> d.isAwesome()).findFirst().orElse(null);
            };
        }
    }
}
```

Third, in Axon Framework 4, the default was to forward events to all entities by default. In Axon Framework 5, this
has changed to only forward events to entities that match the routing key. You can always customize this behavior
by providing a custom `@EntityMember#eventRoutingResolver`:

```java
public abstract class Project {

    @EntityMember(eventTargetMatcher = CustomEventTargetMatcher.class)
    private List<Milestone> features = new ArrayList<>();

    private static class CustomEventTargetMatcher implements EventTargetMatcherDefinition {

        @Nonnull
        @Override
        public <E> EventTargetMatcher<E> createEventRoutingResolver(@Nonnull AnnotatedEntityModel<E> entity,
                                                                    @Nonnull Member member) {
            return (entity, message, ctx) -> {
                return entity.isMostImportantMilestone();
            };
        }
    }
}
```

Fourth, `@EntityMember` can now be used on fields with a simple type, or a `List`. Other types of collections can
currently not be used.
This is due to a limitation of the immutability of child entities that we now support. We might support this in the
future, but for now, we recommend using a `List` or a simple type.

### Exception mapping

With the change from Aggregate to Entity, we've also changed some exceptions. If you depend on these
exceptions, you will need to change your code. The following table shows the changes:

| Old Exception                                                          | New Exception                                                     |
|------------------------------------------------------------------------|-------------------------------------------------------------------|
| `org.axonframework.modelling.command.AggregateEntityNotFoundException` | `org.axonframework.modelling.entity.ChildEntityNotFoundException` |

### Spring Configuration

AF5 fosters auto-detection and auto-configuration of entities, command and message handlers in Spring environment. To
not rely on the old `@Aggregate` stereotype, we introduced the
`@EventSourced` annotation. The `@EventSourced` annotation is still used as a Spring meta-annotation for a prototype
scoped component and now is additionally is meta-annotated with `@EventSourcedEntity` ( replicating all its attributes.)
This effectively means that you only need to put the `@EventSourced` annotation to your entity and the remaining
configuration will be executed by Spring Auto-Configuration. The following attributes are available:

| Attribute                    | Type                                                   | Description                                                                                      |
|------------------------------|--------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| `type`                       | `String`                                               | Defines the type of the entity. If not provided, defaults to the simple class name.              |
| `idType`                     | `Class<?>`                                             | Defines the type of the identity of the entity. If not provided, defaults to `java.lang.String`. |
| `tagKey`                     | `String`                                               | See `EventSourcedEntity#tagKey`                                                                  |
| `concreteTypes`              | `Class<?>[]`                                           | See `EventSourcedEntity#concreteTypes`                                                           |
| `criteriaResolverDefinition` | `Class<? extends CriteriaResolverDefinition>`          | See `EventSourcedEntity#criteriaResolverDefinition`                                              |
| `entityFactoryDefinition`    | `Class<? extends EventSourcedEntityFactoryDefinition>` | See `EventSourcedEntity#entityFactoryDefinition`                                                 |
| `entityIdResolverDefinition` | `Class<? extends EntityIdResolverDefinition>`          | See `EventSourcedEntity#entityIdResolverDefinition`.                                             |

## Test Fixtures

The `axon-test` module of Axon Framework has historically provided two different test fixtures:

1. The `AggregateTestFixture`
2. The `SagaTestFixture`

Both provide a given-when-then style of testing, based on the messages going in and out of the aggregate and saga.
Although practical, we have encountered a couple of predicaments with this style over the years:

1. It is very easy to miss a part of the application configuration with the test fixtures. Although the fixtures have
   numerous registration methods for all things important with aggregate and saga testing, this does not resolve the
   case that somebody might simply forget to add the configuration in both the production and the test scenario.
2. Testing is limited to aggregates and sagas. Hence, Event Handling Components, or Projectors/Projections, do not have
   testing support at all.
3. The test fixtures do not support a form of integration testing. Differently put, it is not possible to validate
   whether the aggregate process (for example) flows through the upcaster process, or triggers snapshots.

In Axon Framework 5, we resolve this by replacing both fixtures with the `AxonTestFixture`. The `AxonTestFixture` is
created by inserting the `ApplicationConfigurer`. From there, it provides the usual given-when-then style of testing.
Any form of message can initiate the given-phase, any form of message can influence the when-phase, and any form of
message can be expected in the then-phase.

By basing the fixture on the `ApplicationConfigurer`, we resolve the concern that users might forget to add
configuration to their fixture that's used in their (production) system. Furthermore, by having the **entire**
`ApplicationConfigurer`, we can easily expand the test fixture to incorporate other areas for testing, like
snapshotting, dead-letter queues, and event scheduling (to name a few). And, lastly, it should serve as an easier
solution towards integration testing an Axon Framework application.

We acknowledge that this shift is a massive breaking changes between Axon Framework 4 and 5. Given the importance of
test suites, we will provide a legacy installment of the old fixtures, albeit deprecated. This way, users are able to
migrate the tests on their own pass.


# Axon Framework 5 — API Changes: ProcessingContext and Unit of Work

> Part of the Axon Framework 4→5 migration guide.
> Covers: complete rewrite of `UnitOfWork` into `ProcessingLifecycle` / `ProcessingContext`, removal of `ThreadLocal`,
> new lifecycle phases, and how legacy components (Sagas) interact with the new context.

## Unit of Work

The `UnitOfWork` interface has been rewritten with roughly three goals in mind:

1. Ensure the API of the `UnitOfWork` easily supports imperative and reactive programming styles.
2. Remove the use of the `ThreadLocal` entirely. This change is paramount for a reactive programming style.
3. Guard users from operations they shouldn't touch. The biggest example of this, was the previous `UnitOfWork#commit`
   operation that **was not** intended to be used by users.

To that end, we broke down the `UnitOfWork` interface into two interfaces and a concrete implementation, being:

1. The `ProcessingLifecycle`, describing methods to register actions into distinct `ProcessingLifeCycle.Phases`, thus
   managing the "lifecycle of a process."
2. The `ProcessingContext`, an implementation of the `ProcessingLifecycle` adding resource management.
3. The `UnitOfWork`, an implementation of the `ProcessingContext` and thus `ProcessingLifecycle`.

The user is intended to interface with the `ProcessingLifecycle` when they need to add actions before/after/during
pre-defined `ProcessingLifecycle.DefaultPhases`.
This will allow us, and them, to customize processes like message handling.
Furthermore, the `ProcessingLifecycle` works with a `CompletableFuture` throughout.

The `ProcessingContext` will in turn provide the space to register resources to be used throughout the
`ProcessingLifecycle`.
Although roughly similar to the previous resource management of the old `UnitOfWork`, we intend this format to replace
the use of the `ThreadLocal`. As such, you will notice that the `ProcessingContext` will become a parameter throughout
virtually **all** infrastructure interfaces Axon Framework provides. This will become most apparent on all message
handlers.

It is the replacement of the interfaces with the old `UnitOfWork`, and the spreading of the `ProcessingContext`
instead of the `UnitOfWork` directly, will ensure that operation that are not intended for the end user cannot be
accessed easily anymore.

To conclude, here is a list of changes to take into account concerning the `UnitOfWork`:

1. Operations like `start()`, `commit()`, and `rollback()` are no longer available for the user directly.
2. The nesting functionality of the old `UnitOfWork` through operations like `parent()` and `root()` are completely
   removed.
3. The `UnitOfWork` used to revolve around a `Message`, which is no longer the case for the `ProcessingContext`/
   `ProcessingLifeycle`. Instead, the new approach revolves around a generic action, that may or may not return a
   result.
4. You are no longer tied to the predefined not-started, started, prepare-commit, commit, after-commit, rollback,
   clean-up, and closed phases. Instead, the default phases now are pre-invocation, invocation, post-invocation,
   prepare-commit, commit, and after-commit.
5. The default phases are ordered through the use of an `int`, with space between them to add action before, after, or
   during any phase.
6. The `rollback` logic has been replaced by an on-error, on-complete, and on-finally flow.
   `ProcessingLifecycle#onError` registers an action to be taken on error, while `whenComplete` registers an action to
   performed when after worked as intended. `ProcessingLifecycle#doFinally` registers an operation that is performed on
   success **and** failure of the `ProcessingLifecycle`.
7. Correlation data management, and thus construction of the initial `Metadata` of any `Message`, is removed entirely.
   This is inline with the `UnitOfWork` no longer revolving around a `Message`.
8. The "current" `UnitOfWork` (including the `CurrentUnitOfWork`) is no longer a concept. Instead, all infrastructure
   components will pass along the current context by containing the `ProcessingContext` as a parameter throughout.

Note that the rewrite of the `UnitOfWork` has caused _a lot_ of API changes and numerous removals. For an exhaustive
list of the latter, please check [here](11-class-reference.md#removed-classes).

## Legacy components

During the development of Axon Framework 5, we have decided that some features move to the legacy package, such as
Sagas. These are features that we think should be either removed, or that deserve a big overhaul in a future version.
Meanwhile, users can thus use the legacy package to continue using these features, while we can focus on the new
features and improvements in Axon Framework 5.

However, even these legacy components have seen some changes. The most notable one is that most of these components
require a `ProcessingContext` to be passed in. This is to ensure good cooperation between old and new parts of the
framework. This means that some changes might be necessary in your code, such as passing in the
`ProcessingContext` to the `InterceptorChain`:

```java
public class MyInterceptingEventHandler {

    @MessageHandlerInterceptor
    public void handle(MyEvent event, InterceptorChain chain, ProcessingContext context) {
        chain.proceedSync(context);
    }
}
```

You are able inject the `ProcessingContext` in any message-handling method, so this is always available. Any code that
uses the old `UnitOfWork` should be rewritten to put resources in this context.

### SagaLifecycle

The Axon Framework 4 `SagaLifecycle` was a `static` utility backed by a `ThreadLocal` (through its `Scope` base
class), pushed onto the current thread for the duration of a single event handler invocation and popped off again
afterward. Axon Framework 5 does not use `ThreadLocal`s, so `SagaLifecycle` in `axon-legacy` is now an **instance**,
scoped to the `ProcessingContext` of the Saga currently handling an event, exposing the same operations
(`associateWith`, `removeAssociationWith`, `end`, `associationValues`) as before, just non-static.

To reach it, declare a `SagaLifecycle`-typed parameter on your `@SagaEventHandler` method; it is resolved
automatically, the same way an `EventAppender` or `CommandDispatcher` parameter is:

```java
public class OrderSaga {

    @SagaEventHandler(associationProperty = "orderId")
    public void on(OrderShippedEvent event, SagaLifecycle lifecycle) {
        lifecycle.associateWith("shipmentId", event.getShipmentId());
        lifecycle.end();
    }
}
```

Old sagas that called `SagaLifecycle.associateWith(...)`, `.end()`, etc. statically from anywhere in the handler body
need this small adjustment. The `Scope` class that backed the old `ThreadLocal` mechanism is removed entirely, since
`SagaLifecycle` was its only remaining consumer.

### SagaStore

The saga stores in `axon-legacy` are an exception worth calling out, because the opposite would be a reasonable
assumption. `SagaStore` keeps its Axon Framework 4 signatures and takes no `ProcessingContext` on any of its five
operations, and neither `InMemorySagaStore`, `JdbcSagaStore`, `JpaSagaStore` nor `CachingSagaStore` is aware of the
processing lifecycle. `JdbcSagaStore` and `JpaSagaStore` join the surrounding transaction through the
`ConnectionProvider` or `EntityManagerProvider` they are given: hand the store the same provider instance the
transaction manager was given, and its writes commit with whatever else that transaction covers. This is the same route
a `TransactionalExecutorProvider` takes internally, so a saga store and an Axon Framework 5 component such as the
`JpaTokenStore` end up in one transaction without the store ever seeing a context.

Note that this places a requirement on the provider rather than on the store: it must resolve the resource bound to the
calling thread's transaction on every call, which `SpringDataSourceConnectionProvider` does and a plain
`DataSourceConnectionProvider` does not. Several units of work can be in flight at once, while the store holds its
provider from construction.

We will provide a migration guide, as well as OpenWrite recipes for these scenarios.

### SagaRepository

Where the store needs no context, the repository does, and all three of its operations take one:

```java
Set<String> find(AssociationValue associationValue, ProcessingContext context);

@Nullable
Saga<T> load(String sagaIdentifier, ProcessingContext context);

Saga<T> createInstance(String sagaIdentifier, Supplier<T> factoryMethod, ProcessingContext context);
```

Axon Framework 4 read the ambient unit of work from `CurrentUnitOfWork`, a thread local, and used it for the two things
this layer does beyond storage: it writes the saga during the prepare-commit phase, which is what puts that write in
the caller's transaction, and it releases the saga's lock when processing completes. `find` takes the context too, even
though it does not use it, so that the interface does not leave an implementor guessing whether the omission means
something.

A repository never creates a context. The event processor does, and hands it down through the component that invokes
the saga.

One wiring note if you modernise a saga while migrating it. An Axon Framework 4 saga received a `CommandGateway`
through a `ResourceInjector` and kept it in a field; that still works, and the handler passes it the
`ProcessingContext` it was invoked with. The Axon Framework 5 route is a `CommandDispatcher` parameter, already bound
to that context, and it needs `CommandDispatcherParameterResolverFactory` to be part of the `ParameterResolverFactory`
the saga's metamodel was built with. That resolver is contributed by a `ConfigurationEnhancer` rather than registered
through `META-INF/services`, so the classpath default `AnnotationSagaMetaModelFactory` uses does not include it: hand
the configured factory to `AnnotatedSagaRepository.Builder#parameterResolverFactory`.

Two consequences of the thread local being gone are worth knowing about.

**Nested units of work are gone, and the saga write is ordered by a phase of its own instead.** Axon Framework 4
distinguished the current unit of work from the outermost one, because a nested unit of work ran its own prepare-commit
and commit early while its after-commit and rollback followed the root. Axon Framework 5 has nothing nested; a branched
`ProcessingContext` forwards every lifecycle registration and every resource to the root, which gives the same result
-- one saga instance per processing session, written once -- without the distinction. What it cannot reproduce is
registering work for a phase that is already running: `ProcessingContext` rejects that, where Axon Framework 4 simply
appended to the phase's handler queue.

That matters because it is exactly what the repository would be doing. `SimpleEventBus` delivers events that were
published with a context during `PREPARE_COMMIT`, and `SubscribingEventProcessor` handles them in that same context, so
a saga behind a subscribing processor is reached from inside `PREPARE_COMMIT`. The repository therefore does not
register its write for `PREPARE_COMMIT` at all. It registers for `AnnotatedSagaRepository.SAGA_WRITE`, a phase ordered
above `PREPARE_COMMIT` and below `COMMIT`:

```java
public static final ProcessingLifecycle.Phase SAGA_WRITE =
        () -> ProcessingLifecycle.DefaultPhases.PREPARE_COMMIT.order() + 5_000;
```

`ProcessingLifecycle.Phase` is an interface over an arbitrary order, so the gaps the default phases leave are usable.
Ordering the write into the one above `PREPARE_COMMIT` keeps it after the handler finished mutating the saga and before
the caller's transaction commits, whichever phase the repository was reached from. Both processors work: a
`PooledStreamingEventProcessor` invokes its components during `INVOCATION`, a `SubscribingEventProcessor` during
`PREPARE_COMMIT`, and both are below `SAGA_WRITE`. The constant is public so you can order your own actions relative to
the write; note that a saga reached from `SAGA_WRITE` itself or later cannot be written.

One difference in the number of writes survives, in a scenario where the stored result is the same either way. Axon
Framework 4's nested unit of work carried its own unsaved-saga bookkeeping, so looking at the same saga again from
inside a nested unit of work made the repository treat it as unsaved and schedule a second write, giving an insert
followed by an update. Axon Framework 5 shares that bookkeeping with the root and orders the single write after every
`PREPARE_COMMIT` action, so it writes once, carrying everything the saga accumulated in the meantime.

**A saga event handler must complete on the thread that invoked it.** Axon Framework 4 could not express anything else:
it invoked the handler and ignored its return value, so an asynchronous result was dropped and never took part in a
transaction. `EventHandlingComponent#handle` can express one and the unit of work awaits it, which would move a saga's
store write off the thread whose transaction it belongs to. `AnnotatedSaga` therefore fails handling with a
`SagaExecutionException` when a handler returns a result that is not done yet. An already completed
`CompletableFuture` is accepted, being indistinguishable from a synchronous return, and a handler that hands work to an
executor and returns `void` is undetectable, as it was in Axon Framework 4.

The same thread affinity applies to the saga's lock. `PessimisticLockFactory` hands out a lock owned by the thread that
took it, and the repository releases it when the context completes, which runs on the invoking thread only when the
`TransactionManager` requires same-thread invocations. `SpringTransactionManager` and `EntityManagerTransactionManager`
both do; a custom one that does not would leak saga locks. Nothing can release such a lock afterwards, so
`LockingSagaRepository` logs an error naming the saga and both threads instead of letting the processing lifecycle
swallow the failure as an anonymous completion handler problem.


### SagaManager

`AbstractSagaManager` is an `EventHandlingComponent` in Axon Framework 5, where it was an `EventHandlerInvoker` in
Axon Framework 4. Three consequences are worth knowing before migrating a saga.

**A failing `@SagaEventHandler` now stops its event processor.** Axon Framework 4 routed the failure to the saga
manager's `ListenerInvocationErrorHandler`, whose default logged it and swallowed it, so the unit of work committed,
the saga was stored as of the exception, and the token advanced past the event. Axon Framework 5 has no such contract:
the failure travels out on the returned `MessageStream`, the unit of work rolls back, the saga is not written and the
token is not advanced. Declaring an `@ExceptionHandler` method on the saga gives the Axon Framework 4 outcome back, and
more precisely than the old handler could, since it is chosen per saga and per exception type rather than for every
saga of a processing group:

```java
public class OrderSaga {

    @SagaEventHandler(associationProperty = "orderId")
    public void on(OrderShippedEvent event) {
        throw new IllegalStateException("...");
    }

    @ExceptionHandler
    public void on(EventMessage event, IllegalStateException exception) {
        logger.error("Saga failed to handle [{}]", event.identifier(), exception);
    }
}
```

Returning normally suppresses the failure. Rethrowing propagates it. One inherited subtlety: when the failing handler
was annotated `@EndSaga`, suppressing the failure does not end the saga, because the saga is ended only after the
handler completes. Axon Framework 4 skipped its `SagaLifecycle.end()` call the same way.

**A `@SagaEventHandler` on a supertype no longer receives its subtypes.** Axon Framework 4 matched handlers by payload
assignability, so a handler for `OrderEvent` also handled `OrderPlaced extends OrderEvent`. An Axon Framework 5 event
processor decides what to deliver from `EventHandlingComponent#supports(QualifiedName)`, which is a set of names
derived from the declared payload types, so a subtype's name is not among them. Declare a handler per concrete event
type. This is not saga-specific: an annotated event handling component behaves the same way.

**Segments are decided by the saga identifier, not by the event.** Axon Framework 4 asked two separate questions, and a
saga manager answered the admission one without consulting the `Segment` at all, leaving `AbstractSagaManager#handle` to
filter saga instances by identifier. Axon Framework 5 folds admission and sequencing into
`sequenceIdentifierFor`, so a saga manager answers `SequencingPolicy.BROADCAST`: every `Segment` is offered every event
the saga type handles, and the one owning the saga identifier acts on it. Deriving the identifier from the event
instead would route a saga's second association value to a segment that does not own the saga, and the event would be
dropped silently.

A saga is still handled once. A `TokenStore` claim makes a segment live on one node, the identifier filter picks one
segment out of those offered the event, and creation is claimed by the single segment matching the initial
`AssociationValue`. Two limits carry over from Axon Framework 4 unchanged: a `SubscribingEventProcessor` has no
segments and no token store, so every node starts its own saga, and a `SagaStore` is keyed on the saga identifier
alone, with no uniqueness constraint over association values.

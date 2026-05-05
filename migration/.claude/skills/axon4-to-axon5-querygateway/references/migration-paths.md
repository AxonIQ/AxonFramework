# Relevant migration-path / API-change excerpts

Quoted from `docs/reference-guide/modules/migration/pages/paths/` and
`axon-5/api-changes/`:

## index.adoc — Import and package changes

The relevant rows for this skill (top-of-chain query-dispatch sites):

| AF4 | AF5 |
|---|---|
| `org.axonframework.queryhandling.QueryGateway` | `org.axonframework.messaging.queryhandling.gateway.QueryGateway` (interface kept, package moved) |
| `org.axonframework.queryhandling` | `org.axonframework.messaging.queryhandling` |
| `org.axonframework.messaging.responsetypes.ResponseType[s]` | **removed** in AF5 |
| `org.axonframework.messaging.MetaData` | `org.axonframework.messaging.core.Metadata` |

> Many classes have been moved to better align with the new modular
> structure. The most notable change is the move of Spring-specific
> and monitoring components into their own extension namespaces, and
> the nesting of core messaging components under the
> `org.axonframework.messaging` package.

## 09-queries-and-minor-changes.md — Query Gateway and Response Types

The single most important paragraph for this skill:

> On top of that, we have eliminated use of the `ResponseType`
> **entirely**. Both from all `QueryMessage` implementations as well
> as from the `QueryGateway`/`QueryBus`. We felt the `ResponseType`
> was cumbersome to deal with and as such viewed as a nuisance for
> the user. Furthermore, it tied our query solution in the JVM space
> when distributing queries, which is **not** desirable at all.
> However, to keep support for querying a single or multiple
> instances, the gateway now has dedicated methods:
>
> 1. `CompletableFuture<R> QueryGateway#query(Object, Class<R>, ProcessingContext)`
> 2. `CompletableFuture<List<R>> QueryGateway#queryMany(Object, Class<R>, ProcessingContext)`

And on streaming / subscription:

> Besides the `CompletableFuture`, the `QueryGateway` has two
> `Publisher`-minded solution as well, being the `streamingQuery` and
> `subscriptionQuery`:
>
> 1. `Publisher<R> QueryGateway#streamingQuery(Object, Class<R>, ProcessingContext)`
> 2. `Publisher<R> QueryGateway#subscriptionQuery(Object, Class<R>, ProcessingContext)`
> 3. `SubscriptionQueryResponse<I, U> QueryGateway#subscriptionQuery(Object, Class<I>, Class<U>, ProcessingContext)`
>
> As is clear, the `ResponseType` did not return for any of these
> methods either. Instead, a **nullable** `ProcessingContext` can be
> given (for example important to have correlation data populated).

## 09-queries-and-minor-changes.md — Scatter-Gather removed

> Notice - Scatter-Gather has been removed!
>
> We decided to remove the Scatter-Gather query support on the
> `QueryBus` and `QueryGateway` due to limited use.

There is no drop-in replacement. Flag every `scatterGather(...)`
call site for the user — the rewrite has to be designed per call
site.

## 09-queries-and-minor-changes.md — Subscription-query redesign

> First and foremost, we tackled the typical touch points of this
> API, being the `QueryGateway` and `QueryUpdateEmitter`. The former
> no longer has `ResponseType` variants on the API at all. Instead,
> the desired `Class` type should be provided and the `QueryGateway`
> will ensure correct conversion. Removing the `ResponseType` has the
> side effect that you are no longer able to, for example, specify a
> collection as the initial result of a subscription query. To keep
> support for 0, 1, or N, we switched the initial result from a
> `Mono` to a `Flux`.

And on the type split:

> By having a `SubscriptionQueryResponseMessages` that uses
> `Messages`, and a `SubscriptionQueryResponse` that uses payloads,
> we keep the symmetry between the bus-and-gateway as is present on
> other infrastructure components of Axon Framework.

Practical implication for callers of the gateway:

- Type returned by `subscriptionQuery(q, I.class, U.class)` is now
  `SubscriptionQueryResponse<I, U>` (payloads), not the AF4 `SubscriptionQueryResult`.
- `initialResult()` returns `Flux<I>`, not `Mono<I>`. Callers
  expecting a single initial result must collapse with `.next()` /
  `.singleOrEmpty()` to keep AF4 semantics.

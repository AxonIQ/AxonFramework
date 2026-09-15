# 1. JacksonConverter rejects Jackson 2 tree nodes (fail loud)

* Status: Accepted
* Date: 2026-06-17

## Context

`JacksonConverter` uses Jackson 3 (`tools.jackson.databind.ObjectMapper`). When a caller
passes a Jackson 2 `com.fasterxml.jackson.databind.JsonNode` and asks for a
`Map<String, Object>`, Jackson 3 does not recognize the input as a tree node (the package
roots are disjoint). It falls back to generic POJO conversion, introspects Jackson 2
`JsonNode`'s `isXxx()` accessor methods, and returns a map keyed by
`isArray` / `isObject` / `isBigDecimal` / etc. instead of the JSON tree contents. No
exception is thrown.

This is a Jackson 2 / Jackson 3 ecosystem mismatch, not a Jackson bug. Both libraries
behave correctly in isolation; they do not compose.

The resulting failure mode is "silent wrong output", which is the worst failure mode in a
data pipeline: operators trust the result, then discover days later that downstream
consumers received nonsense.

## Decision Drivers

* The fix must turn a silent wrong-output bug into a loud, diagnosable failure.
* The fix must keep the smallest possible scope and avoid an implicit commitment to
  cross-version Jackson support.
* `JacksonConverter` must not gain a compile-time dependency on Jackson 2.

## Considered Options

1. **Fail loud.** Detect Jackson 2 tree nodes and throw a `ConversionException` with a
   remediation hint.
2. **Auto-bridge.** Serialize the input via Jackson 2, then re-parse via Jackson 3
   internally so existing user code "just works".
3. **Sibling `Jackson2Converter` artifact.** Officially support Jackson 2 through a
   separate converter.
4. **Document only.** Leave the behavior in place and warn against it in the Javadoc.

## Decision

Chosen option: **Option 1, fail loud**, because it is the minimum scope that stops the
silent failure and the only option that does not commit the framework to cross-version
Jackson support.

A defensive check at the top of `convert(...)` detects Jackson 2 `JsonNode` inputs by
resolving `com.fasterxml.jackson.databind.JsonNode` through the input type's own class
loader (so this class keeps no compile-time dependency on Jackson 2) and testing
assignability. The per-type answer is cached in a `ClassValue`, so the lookup runs at
most once per input class. When the input is a Jackson 2 tree node, the converter throws
a `ConversionException` with a clear remediation hint instead of running the broken
conversion path.

```java
if (isForeignJacksonTreeNode(sourceType)) {
    throw new ConversionException(
            "Cannot convert input of type '" + sourceType.getName() + "' ... "
            + "The input is a Jackson 2 tree node ... "
            + "Serialize the input to a JSON String or byte[] before conversion, "
            + "or use a Jackson 2-based Converter."
    );
}
```

A regression test (`convertRefusesJackson2TreeNodeRatherThanSilentlyIntrospectingItAsAPojo`)
pins this behavior. Verified by reverting the fix locally: the test fails when the check
is absent.

### Why the alternatives were rejected

**Option 2, auto-bridge** would make existing user code "just work", but it makes AF5
silently maintain compatibility with every Jackson 2 feature the user might use. Custom
modules, polymorphic deserialization, custom serializers, deprecated configuration knobs:
they all become part of `JacksonConverter`'s effective contract. Six months in, the file
accumulates workarounds for Jackson 2 quirks and reviewers cannot tell whether they are
allowed to change anything. The bridge looks free; the support contract is not.

**Option 3, sibling `Jackson2Converter` artifact** is the right answer if the team wants
to officially support Jackson 2. It is a piece of work, not a bug fix. Bundling it with
the silent-output fix conflates two decisions: "stop producing garbage" (uncontested) and
"officially support Jackson 2" (a strategic call). They should be decided separately.

**Option 4, document only** leaves the bug in place. Documented footguns are still
footguns; most users discover the wrong-output behavior before they read the Javadoc.

## Consequences

A user who reads the exception immediately understands:

* what the converter is (Jackson 3),
* what their input is (Jackson 2),
* what to do (serialize to String / byte[], or use a Jackson 2 converter).

What this decision does NOT do:

* It does not add Jackson 2 support. Users hit a clear error and have to bridge
  themselves.
* It does not change the behavior of any other input type. POJO-to-Map via Jackson 3's
  standard `convertValue(...)` still works as documented.
* It does not catch other foreign tree types (Gson `JsonElement`, etc.). Only Jackson 2's
  `JsonNode`, because that is the one we hit and the only one in widespread Java JSON use
  today that overlaps with Jackson 3's adoption window.

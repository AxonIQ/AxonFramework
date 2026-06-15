# JacksonConverter + Jackson 2 inputs: why we chose fail-loud

Discussion of the fix landed in `JacksonConverter` for the silent-wrong-output bug found
when a Jackson 2 `JsonNode` is passed to the (Jackson 3-based) `JacksonConverter`.

## The bug in one paragraph

`JacksonConverter` uses Jackson 3 (`tools.jackson.databind.ObjectMapper`). When a caller
passes a Jackson 2 `com.fasterxml.jackson.databind.JsonNode` and asks for a
`Map<String, Object>`, Jackson 3 does not recognize the input as a tree node (the package
roots are disjoint). It falls back to generic POJO conversion, introspects Jackson 2
`JsonNode`'s `isXxx()` accessor methods, and returns a map keyed by
`isArray` / `isObject` / `isBigDecimal` / etc. instead of the JSON tree contents. No
exception is thrown.

This is a Jackson 2 / Jackson 3 ecosystem mismatch, not a Jackson bug. Both libraries
behave correctly in isolation; they don't compose.

## What we chose: Option 1 — fail loud

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
pins this behavior. Verified by reverting the fix locally — the test fails when the check
is absent.

## Why Option 1

Two material reasons:

### 1. The minimum scope to stop the silent failure

The bug is "silent wrong output." That's the worst failure mode in a data pipeline —
operators trust the result, then discover days later that downstream consumers got
nonsense. The fix's job is to make this loud. Option 1 does exactly that and nothing more.

A user who reads the exception immediately understands:
- What the converter is (Jackson 3).
- What their input is (Jackson 2).
- What to do (serialize to String / byte[], or use a Jackson 2 converter).

### 2. No commitment to cross-version Jackson support

The alternatives all carry a hidden commitment.

**Option 2 — auto-bridge** (serialize via Jackson 2 → re-parse via Jackson 3 internally)
would make existing user code "just work," but it makes AF5 silently maintain
compatibility with every Jackson 2 feature the user might use. Custom modules,
polymorphic deserialization, custom serializers, deprecated configuration knobs — they all
become part of `JacksonConverter`'s effective contract. Six months in, the file has
accumulated workarounds for Jackson 2 quirks and reviewers can't tell whether they're
allowed to change anything. The bridge looks free; the support contract isn't.

**Option 3 — sibling `Jackson2Converter` artifact** is the right answer IF the team
wants to officially support Jackson 2. It's a piece of work, not a bug fix. Bundling it
with the silent-output fix conflates two decisions: "stop producing garbage" (uncontested)
and "officially support Jackson 2" (a strategic call). They should be decided separately.

**Option 4 — document only** leaves the bug in place. Documented footguns are still
footguns; most users discover the wrong-output behavior before they read the Javadoc.

### What Option 1 does NOT do

- Doesn't add Jackson 2 support. Users hit a clear error and have to bridge themselves.
- Doesn't change the behavior of any other input type. POJO-to-Map via Jackson 3's
  standard `convertValue(...)` still works as documented.
- Doesn't catch other foreign tree types (Gson `JsonElement`, etc.). Only Jackson 2's
  `JsonNode`, because that's the one we hit and the only one in widespread Java JSON use
  today that overlaps with Jackson 3's adoption window.

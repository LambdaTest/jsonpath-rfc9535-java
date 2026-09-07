# jsonpath-rfc9535-java

A **zero-dependency** Java implementation of [RFC 9535 (JSONPath: Query Expressions for JSON)](https://www.rfc-editor.org/rfc/rfc9535.html), including the I-Regexp ([RFC 9485](https://www.rfc-editor.org/rfc/rfc9485.html)) `match()`/`search()` function extensions.

**Compliance: 703/703** cases of the official [JSONPath Compliance Test Suite](https://github.com/jsonpath-standard/jsonpath-compliance-test-suite).

## Usage

```java
import io.github.lambdatest.jsonpath.JsonPath;

JsonPath path = JsonPath.parse("$.result[?(@.timeSlots && @.gender=='Male')].employeeId");

// document model: Map<String,Object> / List<Object> / String / Number / Boolean / null
List<Object> values = path.query(document);

// values + RFC 9535 normalized paths
List<JsonPath.Match> matches = path.queryNodes(document);
```

`JsonPath.parse` throws `JsonPathException` for any query that is not well-formed
or not valid per the RFC (strict ABNF, integer bounds, function type system,
singular-query restrictions — all enforced at parse time).

## Design notes

- **Zero runtime dependencies.** The library operates on the plain-Java JSON
  model (`Map`/`List`/scalars); bring any JSON parser you like. Insertion-ordered
  maps (`LinkedHashMap`) are recommended so object member order follows the document.
- **Numbers** are compared numerically per the RFC (`1 == 1.0`); `BigDecimal` is
  used internally so precision is never silently lost.
- **I-Regexp** patterns are validated against the RFC 9485 grammar and translated
  to `java.util.regex` (`.` excludes line terminators; Java-only extensions can't
  activate). Invalid patterns make `match()`/`search()` return false, per spec.
- **Strings** compare in Unicode code point order; `length()` counts code points.

## Compliance testing

```
javac -d target/classes $(find src -name "*.java")
java -cp target/classes io.github.lambdatest.jsonpath.cts.CtsRunner /path/to/cts.json
```

The CTS runner (and its bundled minimal JSON reader) live under `src/test` and
keep the whole repository dependency-free.

## License

MIT © LambdaTest

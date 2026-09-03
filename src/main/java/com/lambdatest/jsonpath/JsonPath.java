package com.lambdatest.jsonpath;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 9535 JSONPath for Java — zero dependencies.
 *
 * <pre>{@code
 * JsonPath path = JsonPath.parse("$.result[?(@.timeSlots && @.gender=='Male')].employeeId");
 * List<Object> values = path.query(document); // document: Map/List/String/Number/Boolean/null
 * }</pre>
 *
 * The JSON document model is plain Java: {@code Map<String,Object>} for objects
 * (insertion-ordered maps recommended), {@code List<Object>} for arrays,
 * {@code String}, {@code Number}, {@code Boolean} and {@code null} for scalars.
 */
public final class JsonPath {
    private final String expression;
    private final List<Ast.Segment> segments;

    private JsonPath(String expression, List<Ast.Segment> segments) {
        this.expression = expression;
        this.segments = segments;
    }

    /** Parse a query. @throws JsonPathException when not well-formed/valid per RFC 9535. */
    public static JsonPath parse(String expression) {
        if (expression == null) throw new JsonPathException("query must not be null", 0);
        return new JsonPath(expression, Parser.parse(expression));
    }

    /** Evaluate and return matched values in document order. */
    public List<Object> query(Object document) {
        List<Object> values = new ArrayList<>();
        for (Evaluator.Node n : new Evaluator(document).evaluate(segments)) {
            values.add(n.value);
        }
        return values;
    }

    /** Evaluate and return (value, normalizedPath) matches in document order. */
    public List<Match> queryNodes(Object document) {
        List<Match> matches = new ArrayList<>();
        for (Evaluator.Node n : new Evaluator(document).evaluate(segments)) {
            matches.add(new Match(n.value, n.path));
        }
        return matches;
    }

    public String expression() {
        return expression;
    }

    @Override
    public String toString() {
        return "JsonPath(" + expression + ")";
    }

    /** A single query match: the value and its RFC 9535 normalized path. */
    public static final class Match {
        public final Object value;
        public final String path;

        Match(Object value, String path) {
            this.value = value;
            this.path = path;
        }
    }
}

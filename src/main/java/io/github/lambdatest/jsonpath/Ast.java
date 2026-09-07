package io.github.lambdatest.jsonpath;

import java.util.List;

/** AST node types for a parsed RFC 9535 query. Internal API. */
final class Ast {
    private Ast() {}

    // ── Segments ─────────────────────────────────────────────────────────
    static final class Segment {
        final boolean descendant;
        final List<Selector> selectors;

        Segment(boolean descendant, List<Selector> selectors) {
            this.descendant = descendant;
            this.selectors = selectors;
        }
    }

    // ── Selectors ────────────────────────────────────────────────────────
    interface Selector {}

    static final class NameSelector implements Selector {
        final String name;
        NameSelector(String name) { this.name = name; }
    }

    static final class WildcardSelector implements Selector {}

    static final class IndexSelector implements Selector {
        final long index;
        IndexSelector(long index) { this.index = index; }
    }

    static final class SliceSelector implements Selector {
        final Long start, end, step; // null = absent
        SliceSelector(Long start, Long end, Long step) {
            this.start = start; this.end = end; this.step = step;
        }
    }

    static final class FilterSelector implements Selector {
        final Expr expr; // LogicalType expression
        FilterSelector(Expr expr) { this.expr = expr; }
    }

    // ── Filter expressions ───────────────────────────────────────────────
    interface Expr {}

    static final class OrExpr implements Expr {
        final List<Expr> operands;
        OrExpr(List<Expr> operands) { this.operands = operands; }
    }

    static final class AndExpr implements Expr {
        final List<Expr> operands;
        AndExpr(List<Expr> operands) { this.operands = operands; }
    }

    static final class NotExpr implements Expr {
        final Expr operand;
        NotExpr(Expr operand) { this.operand = operand; }
    }

    /** Existence test on an embedded query, or truthiness of a logical function. */
    static final class TestExpr implements Expr {
        final Expr target; // QueryExpr or FunctionExpr
        TestExpr(Expr target) { this.target = target; }
    }

    static final class ComparisonExpr implements Expr {
        final Expr left, right; // comparables: Literal / QueryExpr(singular) / FunctionExpr(ValueType)
        final String op;        // == != < <= > >=
        ComparisonExpr(Expr left, String op, Expr right) {
            this.left = left; this.op = op; this.right = right;
        }
    }

    static final class Literal implements Expr {
        final Object value; // String / Long / Double / Boolean / null
        Literal(Object value) { this.value = value; }
    }

    /** An embedded query: relative (@) or absolute ($). */
    static final class QueryExpr implements Expr {
        final boolean relative;
        final List<Segment> segments;
        final boolean singular; // only name/index single-selector child segments
        QueryExpr(boolean relative, List<Segment> segments, boolean singular) {
            this.relative = relative; this.segments = segments; this.singular = singular;
        }
    }

    static final class FunctionExpr implements Expr {
        final String name;
        final List<Expr> args;
        final Functions.Type returnType;
        FunctionExpr(String name, List<Expr> args, Functions.Type returnType) {
            this.name = name; this.args = args; this.returnType = returnType;
        }
    }
}

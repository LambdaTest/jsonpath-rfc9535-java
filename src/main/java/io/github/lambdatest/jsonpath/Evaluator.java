package io.github.lambdatest.jsonpath;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RFC 9535 evaluator over the plain-Java JSON model:
 * Map&lt;String,Object&gt; / List&lt;Object&gt; / String / Number / Boolean / null.
 */
final class Evaluator {
    private final Object root;

    Evaluator(Object root) {
        this.root = root;
    }

    /** A node: value + normalized path. */
    static final class Node {
        final Object value;
        final String path;
        Node(Object value, String path) {
            this.value = value; this.path = path;
        }
    }

    List<Node> evaluate(List<Ast.Segment> segments) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(new Node(root, "$"));
        for (Ast.Segment seg : segments) {
            nodes = applySegment(nodes, seg);
        }
        return nodes;
    }

    private List<Node> applySegment(List<Node> input, Ast.Segment seg) {
        List<Node> out = new ArrayList<>();
        for (Node n : input) {
            if (seg.descendant) {
                List<Node> visits = new ArrayList<>();
                collectDescendants(n, visits);
                for (Node v : visits) {
                    for (Ast.Selector sel : seg.selectors) {
                        applySelector(v, sel, out);
                    }
                }
            } else {
                for (Ast.Selector sel : seg.selectors) {
                    applySelector(n, sel, out);
                }
            }
        }
        return out;
    }

    /** Pre-order: the node itself, then descendants in document order. */
    private void collectDescendants(Node n, List<Node> out) {
        out.add(n);
        if (n.value instanceof List) {
            List<?> list = (List<?>) n.value;
            for (int i = 0; i < list.size(); i++) {
                collectDescendants(new Node(list.get(i), n.path + "[" + i + "]"), out);
            }
        } else if (n.value instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) n.value).entrySet()) {
                collectDescendants(new Node(e.getValue(), n.path + nameSeg((String) e.getKey())), out);
            }
        }
    }

    private void applySelector(Node n, Ast.Selector sel, List<Node> out) {
        Object v = n.value;
        if (sel instanceof Ast.NameSelector) {
            if (v instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) v;
                String name = ((Ast.NameSelector) sel).name;
                if (m.containsKey(name)) {
                    out.add(new Node(m.get(name), n.path + nameSeg(name)));
                }
            }
        } else if (sel instanceof Ast.WildcardSelector) {
            if (v instanceof List) {
                List<?> list = (List<?>) v;
                for (int i = 0; i < list.size(); i++) {
                    out.add(new Node(list.get(i), n.path + "[" + i + "]"));
                }
            } else if (v instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    out.add(new Node(e.getValue(), n.path + nameSeg((String) e.getKey())));
                }
            }
        } else if (sel instanceof Ast.IndexSelector) {
            if (v instanceof List) {
                List<?> list = (List<?>) v;
                long idx = ((Ast.IndexSelector) sel).index;
                long norm = idx >= 0 ? idx : list.size() + idx;
                if (norm >= 0 && norm < list.size()) {
                    out.add(new Node(list.get((int) norm), n.path + "[" + norm + "]"));
                }
            }
        } else if (sel instanceof Ast.SliceSelector) {
            if (v instanceof List) {
                applySlice((List<?>) v, (Ast.SliceSelector) sel, n, out);
            }
        } else if (sel instanceof Ast.FilterSelector) {
            Ast.Expr expr = ((Ast.FilterSelector) sel).expr;
            if (v instanceof List) {
                List<?> list = (List<?>) v;
                for (int i = 0; i < list.size(); i++) {
                    if (truthy(evalLogical(expr, list.get(i)))) {
                        out.add(new Node(list.get(i), n.path + "[" + i + "]"));
                    }
                }
            } else if (v instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    if (truthy(evalLogical(expr, e.getValue()))) {
                        out.add(new Node(e.getValue(), n.path + nameSeg((String) e.getKey())));
                    }
                }
            }
        }
    }

    /** RFC 9535 §2.3.4.2.2 slice algorithm. */
    private void applySlice(List<?> list, Ast.SliceSelector s, Node n, List<Node> out) {
        long len = list.size();
        long step = s.step == null ? 1 : s.step;
        if (step == 0) return;
        long start = s.start != null ? s.start : (step > 0 ? 0 : len - 1);
        long end = s.end != null ? s.end : (step > 0 ? len : -len - 1);
        long nStart = start >= 0 ? start : len + start;
        long nEnd = end >= 0 ? end : len + end;
        if (step > 0) {
            long lower = Math.min(Math.max(nStart, 0), len);
            long upper = Math.min(Math.max(nEnd, 0), len);
            for (long i = lower; i < upper; i += step) {
                out.add(new Node(list.get((int) i), n.path + "[" + i + "]"));
            }
        } else {
            long upper = Math.min(Math.max(nStart, -1), len - 1);
            long lower = Math.min(Math.max(nEnd, -1), len - 1);
            for (long i = upper; i > lower; i += step) {
                out.add(new Node(list.get((int) i), n.path + "[" + i + "]"));
            }
        }
    }

    // ── filter evaluation ────────────────────────────────────────────────
    private boolean truthy(boolean b) {
        return b;
    }

    private boolean evalLogical(Ast.Expr e, Object current) {
        if (e instanceof Ast.OrExpr) {
            for (Ast.Expr op : ((Ast.OrExpr) e).operands) {
                if (evalLogical(op, current)) return true;
            }
            return false;
        }
        if (e instanceof Ast.AndExpr) {
            for (Ast.Expr op : ((Ast.AndExpr) e).operands) {
                if (!evalLogical(op, current)) return false;
            }
            return true;
        }
        if (e instanceof Ast.NotExpr) {
            return !evalLogical(((Ast.NotExpr) e).operand, current);
        }
        if (e instanceof Ast.TestExpr) {
            Ast.Expr t = ((Ast.TestExpr) e).target;
            if (t instanceof Ast.QueryExpr) {
                return !queryNodes((Ast.QueryExpr) t, current).isEmpty();
            }
            return logicalFunction((Ast.FunctionExpr) t, current);
        }
        if (e instanceof Ast.ComparisonExpr) {
            return evalComparison((Ast.ComparisonExpr) e, current);
        }
        if (e instanceof Ast.QueryExpr) { // bare query used as a logical argument
            return !queryNodes((Ast.QueryExpr) e, current).isEmpty();
        }
        if (e instanceof Ast.FunctionExpr) {
            return logicalFunction((Ast.FunctionExpr) e, current);
        }
        throw new IllegalStateException("not a logical expression: " + e);
    }

    private boolean logicalFunction(Ast.FunctionExpr f, Object current) {
        Object r = evalFunction(f, current);
        return r == Boolean.TRUE;
    }

    private boolean evalComparison(Ast.ComparisonExpr c, Object current) {
        Object left = evalComparable(c.left, current);
        Object right = evalComparable(c.right, current);
        switch (c.op) {
            case "==": return compareEquals(left, right);
            case "!=": return !compareEquals(left, right);
            case "<":  return bothPresent(left, right) && JsonUtil.lessThan(left, right);
            case ">":  return bothPresent(left, right) && JsonUtil.lessThan(right, left);
            case "<=": return compareEquals(left, right) || (bothPresent(left, right) && JsonUtil.lessThan(left, right));
            case ">=": return compareEquals(left, right) || (bothPresent(left, right) && JsonUtil.lessThan(right, left));
            default: throw new IllegalStateException(c.op);
        }
    }

    private static boolean bothPresent(Object a, Object b) {
        return a != Functions.NOTHING && b != Functions.NOTHING;
    }

    private static boolean compareEquals(Object a, Object b) {
        if (a == Functions.NOTHING || b == Functions.NOTHING) {
            return a == Functions.NOTHING && b == Functions.NOTHING;
        }
        return JsonUtil.deepEquals(a, b);
    }

    /** Comparable evaluation: literal, singular query (value or NOTHING), ValueType function. */
    private Object evalComparable(Ast.Expr e, Object current) {
        if (e instanceof Ast.Literal) return ((Ast.Literal) e).value;
        if (e instanceof Ast.QueryExpr) {
            List<Node> nodes = queryNodes((Ast.QueryExpr) e, current);
            return nodes.size() == 1 ? nodes.get(0).value : Functions.NOTHING;
        }
        if (e instanceof Ast.FunctionExpr) return evalFunction((Ast.FunctionExpr) e, current);
        throw new IllegalStateException("not comparable: " + e);
    }

    private List<Node> queryNodes(Ast.QueryExpr q, Object current) {
        Evaluator sub = q.relative ? new Evaluator(current) : new Evaluator(root);
        // relative queries keep the true root for any nested absolute queries
        if (q.relative) sub.absoluteRoot = this.absoluteRoot != null ? this.absoluteRoot : this.root;
        else sub.absoluteRoot = this.absoluteRoot;
        return sub.evaluate(q.segments);
    }

    private Object absoluteRoot; // root document when evaluating a detached relative query

    private Object evalFunction(Ast.FunctionExpr f, Object current) {
        switch (f.name) {
            case "length":
                return Functions.length(evalValueArg(f.args.get(0), current));
            case "count":
                return (long) evalNodesArg(f.args.get(0), current).size();
            case "value": {
                List<Node> nodes = evalNodesArg(f.args.get(0), current);
                return nodes.size() == 1 ? nodes.get(0).value : Functions.NOTHING;
            }
            case "match":
                return Functions.match(evalValueArg(f.args.get(0), current), evalValueArg(f.args.get(1), current));
            case "search":
                return Functions.search(evalValueArg(f.args.get(0), current), evalValueArg(f.args.get(1), current));
            default:
                throw new IllegalStateException("unknown function " + f.name);
        }
    }

    private Object evalValueArg(Ast.Expr e, Object current) {
        return evalComparable(e, current);
    }

    private List<Node> evalNodesArg(Ast.Expr e, Object current) {
        if (e instanceof Ast.QueryExpr) return queryNodes((Ast.QueryExpr) e, current);
        if (e instanceof Ast.FunctionExpr) {
            throw new IllegalStateException("no standard function returns NodesType");
        }
        throw new IllegalStateException("not a nodes argument");
    }

    // ── normalized path helpers ──────────────────────────────────────────
    private static String nameSeg(String name) {
        StringBuilder sb = new StringBuilder("['");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            switch (c) {
                case '\'': sb.append("\\'"); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append("']").toString();
    }
}

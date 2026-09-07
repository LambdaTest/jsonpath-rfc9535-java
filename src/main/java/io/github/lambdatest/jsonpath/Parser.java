package io.github.lambdatest.jsonpath;

import java.util.ArrayList;
import java.util.List;

/**
 * Strict recursive-descent parser for the RFC 9535 ABNF. Enforces
 * well-formedness AND validity (integer bounds, function type system,
 * singular-query restrictions) at parse time.
 */
final class Parser {
    static final long MAX_SAFE = 9007199254740991L; // 2^53 - 1

    private final String src;
    private int pos;

    private Parser(String src) {
        this.src = src;
    }

    static List<Ast.Segment> parse(String query) {
        Parser p = new Parser(query);
        List<Ast.Segment> segments = p.parseQuery();
        return segments;
    }

    // ── query = "$" segments ─────────────────────────────────────────────
    private List<Ast.Segment> parseQuery() {
        if (!eat('$')) throw err("query must start with '$'");
        List<Ast.Segment> segments = parseSegments();
        if (pos != src.length()) throw err("unexpected trailing characters");
        return segments;
    }

    /** segments = *(S segment) — used by the root query and embedded queries. */
    private List<Ast.Segment> parseSegments() {
        List<Ast.Segment> segments = new ArrayList<>();
        while (true) {
            int save = pos;
            skipWs();
            if (pos >= src.length()) { pos = save; break; }
            char c = src.charAt(pos);
            if (c == '.' || c == '[') {
                segments.add(parseSegment());
            } else {
                pos = save;
                break;
            }
        }
        return segments;
    }

    private Ast.Segment parseSegment() {
        if (peek('.')) {
            if (peekAt(1, '.')) {
                pos += 2; // ".."
                // descendant-segment = ".." (bracketed / wildcard / shorthand)
                if (pos < src.length() && src.charAt(pos) == '[') {
                    return new Ast.Segment(true, parseBracketed());
                }
                if (eat('*')) return new Ast.Segment(true, listOf(new Ast.WildcardSelector()));
                String name = parseShorthand();
                return new Ast.Segment(true, listOf(new Ast.NameSelector(name)));
            }
            pos++; // "."
            if (eat('*')) return new Ast.Segment(false, listOf(new Ast.WildcardSelector()));
            String name = parseShorthand();
            return new Ast.Segment(false, listOf(new Ast.NameSelector(name)));
        }
        if (peek('[')) {
            return new Ast.Segment(false, parseBracketed());
        }
        throw err("expected segment");
    }

    /** bracketed-selection = "[" S selector *(S "," S selector) S "]" */
    private List<Ast.Selector> parseBracketed() {
        expect('[');
        List<Ast.Selector> selectors = new ArrayList<>();
        skipWs();
        selectors.add(parseSelector());
        while (true) {
            skipWs();
            if (eat(']')) return selectors;
            if (eat(',')) {
                skipWs();
                selectors.add(parseSelector());
                continue;
            }
            throw err("expected ',' or ']' in bracketed selection");
        }
    }

    private Ast.Selector parseSelector() {
        if (pos >= src.length()) throw err("expected selector");
        char c = src.charAt(pos);
        if (c == '"' || c == '\'') {
            return new Ast.NameSelector(parseStringLiteral());
        }
        if (c == '*') {
            pos++;
            return new Ast.WildcardSelector();
        }
        if (c == '?') {
            pos++;
            skipWs();
            Ast.Expr expr = parseLogicalOr();
            requireLogicalContext(expr);
            return new Ast.FilterSelector(expr);
        }
        // index or slice — parse optional int, then check for ':'
        Long first = null;
        if (c == '-' || (c >= '0' && c <= '9')) {
            first = parseIntToken();
        }
        int save = pos;
        skipWs();
        if (pos < src.length() && src.charAt(pos) == ':') {
            pos++; // ':'
            return parseSliceRest(first);
        }
        pos = save;
        if (first == null) throw err("expected selector");
        return new Ast.IndexSelector(first);
    }

    /** After "start? S ':'": S [end S] [":" S [step]] */
    private Ast.SliceSelector parseSliceRest(Long start) {
        skipWs();
        Long end = null;
        if (pos < src.length() && (src.charAt(pos) == '-' || isDigit(src.charAt(pos)))) {
            end = parseIntToken();
        }
        int save = pos;
        skipWs();
        Long step = null;
        if (pos < src.length() && src.charAt(pos) == ':') {
            pos++;
            skipWs();
            if (pos < src.length() && (src.charAt(pos) == '-' || isDigit(src.charAt(pos)))) {
                step = parseIntToken();
            }
        } else {
            pos = save;
        }
        return new Ast.SliceSelector(start, end, step);
    }

    /** int = "0" / (["-"] DIGIT1 *DIGIT), |i| <= 2^53-1. Used by index & slice parts. */
    private long parseIntToken() {
        int startPos = pos;
        boolean neg = eat('-');
        if (pos >= src.length() || !isDigit(src.charAt(pos))) throw err("expected integer");
        char first = src.charAt(pos);
        StringBuilder sb = new StringBuilder();
        while (pos < src.length() && isDigit(src.charAt(pos))) sb.append(src.charAt(pos++));
        String digits = sb.toString();
        if (digits.length() > 1 && first == '0') { pos = startPos; throw err("leading zeros are not allowed"); }
        if (neg && digits.equals("0")) { pos = startPos; throw err("-0 is not a valid integer"); }
        long value;
        try {
            value = Long.parseLong((neg ? "-" : "") + digits);
        } catch (NumberFormatException e) {
            throw err("integer out of interoperable range");
        }
        if (value > MAX_SAFE || value < -MAX_SAFE) throw err("integer out of interoperable range");
        return value;
    }

    /** member-name-shorthand = name-first *name-char */
    private String parseShorthand() {
        if (pos >= src.length()) throw err("expected member name");
        int cp = src.codePointAt(pos);
        if (!isNameFirst(cp)) throw err("invalid member name shorthand");
        StringBuilder sb = new StringBuilder();
        sb.appendCodePoint(cp);
        pos += Character.charCount(cp);
        while (pos < src.length()) {
            cp = src.codePointAt(pos);
            if (!isNameChar(cp)) break;
            sb.appendCodePoint(cp);
            pos += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static boolean isNameFirst(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || cp == '_'
                || (cp >= 0x80 && cp <= 0xD7FF) || (cp >= 0xE000 && cp <= 0x10FFFF);
    }

    private static boolean isNameChar(int cp) {
        return isNameFirst(cp) || (cp >= '0' && cp <= '9');
    }

    // ── string literals ──────────────────────────────────────────────────
    private String parseStringLiteral() {
        char quote = src.charAt(pos++);
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw err("unterminated string literal");
            char c = src.charAt(pos);
            if (c == quote) { pos++; return sb.toString(); }
            if (c == '\\') {
                pos++;
                if (pos >= src.length()) throw err("bad escape");
                char e = src.charAt(pos++);
                switch (e) {
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '/': sb.append('/'); break;
                    case '\\': sb.append('\\'); break;
                    case 'u': sb.append(parseUnicodeEscape()); break;
                    case '\'':
                        if (quote != '\'') throw err("\\' only valid in single-quoted strings");
                        sb.append('\''); break;
                    case '"':
                        if (quote != '"') throw err("\\\" only valid in double-quoted strings");
                        sb.append('"'); break;
                    default: throw err("invalid escape '\\" + e + "'");
                }
                continue;
            }
            if (c <= 0x1F) throw err("unescaped control character in string literal");
            sb.append(c);
            pos++;
        }
    }

    /** \\uXXXX with mandatory surrogate pairing per the ABNF. */
    private String parseUnicodeEscape() {
        int high = readHex4();
        if (Character.isHighSurrogate((char) high)) {
            // must be followed by \\uDC00-DFFF
            if (pos + 1 < src.length() && src.charAt(pos) == '\\' && src.charAt(pos + 1) == 'u') {
                int save = pos;
                pos += 2;
                int low = readHex4();
                if (Character.isLowSurrogate((char) low)) {
                    return new String(Character.toChars(Character.toCodePoint((char) high, (char) low)));
                }
                pos = save;
            }
            throw err("lone high surrogate in \\u escape");
        }
        if (Character.isLowSurrogate((char) high)) throw err("lone low surrogate in \\u escape");
        return String.valueOf((char) high);
    }

    private int readHex4() {
        if (pos + 4 > src.length()) throw err("bad \\u escape");
        int v = 0;
        for (int i = 0; i < 4; i++) {
            char c = src.charAt(pos++);
            int d = Character.digit(c, 16);
            if (d < 0) throw err("bad \\u escape");
            v = (v << 4) | d;
        }
        return v;
    }

    // ── filter expressions ───────────────────────────────────────────────
    private Ast.Expr parseLogicalOr() {
        List<Ast.Expr> ops = new ArrayList<>();
        ops.add(parseLogicalAnd());
        while (true) {
            int save = pos;
            skipWs();
            if (pos + 1 < src.length() && src.charAt(pos) == '|' && src.charAt(pos + 1) == '|') {
                pos += 2;
                skipWs();
                ops.add(parseLogicalAnd());
            } else {
                pos = save;
                break;
            }
        }
        return ops.size() == 1 ? ops.get(0) : new Ast.OrExpr(ops);
    }

    private Ast.Expr parseLogicalAnd() {
        List<Ast.Expr> ops = new ArrayList<>();
        ops.add(parseBasicExpr());
        while (true) {
            int save = pos;
            skipWs();
            if (pos + 1 < src.length() && src.charAt(pos) == '&' && src.charAt(pos + 1) == '&') {
                pos += 2;
                skipWs();
                ops.add(parseBasicExpr());
            } else {
                pos = save;
                break;
            }
        }
        return ops.size() == 1 ? ops.get(0) : new Ast.AndExpr(ops);
    }

    /** basic-expr = paren-expr / comparison-expr / test-expr */
    private Ast.Expr parseBasicExpr() {
        boolean negated = false;
        if (peek('!')) {
            pos++;
            skipWs();
            negated = true;
        }
        if (peek('(')) {
            pos++;
            skipWs();
            Ast.Expr inner = parseLogicalOr();
            requireLogicalContext(inner);
            skipWs();
            expect(')');
            Ast.Expr expr = inner;
            // a parenthesised expr cannot be a comparison operand, so check for
            // a stray comparison op only to give a strict error
            return negated ? new Ast.NotExpr(expr) : expr;
        }
        // parse one "item": literal / query / function
        Ast.Expr first = parseItem();
        int save = pos;
        skipWs();
        String op = peekComparisonOp();
        if (op != null) {
            if (negated) throw err("'!' cannot be applied to a comparison");
            pos += op.length();
            skipWs();
            Ast.Expr right = parseItem();
            requireComparable(first);
            requireComparable(right);
            return new Ast.ComparisonExpr(first, op, right);
        }
        pos = save;
        // test-expr: must be a query or a function returning Logical/Nodes
        if (first instanceof Ast.Literal) throw err("a literal must be part of a comparison");
        if (first instanceof Ast.FunctionExpr) {
            Functions.Type rt = ((Ast.FunctionExpr) first).returnType;
            if (rt == Functions.Type.VALUE) throw err("ValueType function result must be compared");
        }
        Ast.Expr test = new Ast.TestExpr(first);
        return negated ? new Ast.NotExpr(test) : test;
    }

    /** literal / filter-query / function-expr — the atoms of filter expressions. */
    private Ast.Expr parseItem() {
        if (pos >= src.length()) throw err("expected expression");
        char c = src.charAt(pos);
        if (c == '"' || c == '\'') return new Ast.Literal(parseStringLiteral());
        if (c == '-' || isDigit(c)) return new Ast.Literal(parseNumberLiteral());
        if (c == '@' || c == '$') {
            pos++;
            List<Ast.Segment> segs = parseSegments();
            return new Ast.QueryExpr(c == '@', segs, isSingular(segs));
        }
        if (matchWord("true")) return new Ast.Literal(Boolean.TRUE);
        if (matchWord("false")) return new Ast.Literal(Boolean.FALSE);
        if (matchWord("null")) return new Ast.Literal(null);
        if (c >= 'a' && c <= 'z') return parseFunctionExpr();
        throw err("unexpected character '" + c + "' in filter expression");
    }

    /** number = (int / "-0") [frac] [exp] — leading zeros invalid, "-0" allowed only with frac/exp?
     *  Per ABNF: number = (int / "-0") [ frac ] [ exp ], so bare "-0" IS a valid number literal. */
    private Object parseNumberLiteral() {
        int start = pos;
        boolean neg = eat('-');
        if (pos >= src.length() || !isDigit(src.charAt(pos))) throw err("expected number");
        StringBuilder digits = new StringBuilder();
        while (pos < src.length() && isDigit(src.charAt(pos))) digits.append(src.charAt(pos++));
        if (digits.length() > 1 && digits.charAt(0) == '0') throw err("leading zeros are not allowed");
        boolean isDouble = false;
        if (pos < src.length() && src.charAt(pos) == '.') {
            pos++;
            isDouble = true;
            if (pos >= src.length() || !isDigit(src.charAt(pos))) throw err("digits required after decimal point");
            while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            pos++;
            isDouble = true;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            if (pos >= src.length() || !isDigit(src.charAt(pos))) throw err("digits required in exponent");
            while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
        }
        String text = src.substring(start, pos);
        if (isDouble) return Double.parseDouble(text);
        long v = Long.parseLong(text);
        return v;
    }

    private Ast.Expr parseFunctionExpr() {
        int start = pos;
        StringBuilder name = new StringBuilder();
        // function-name = LCALPHA *(LCALPHA / DIGIT / "_")
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= 'a' && c <= 'z') || c == '_' || (isDigit(c) && name.length() > 0)) {
                name.append(c);
                pos++;
            } else break;
        }
        if (name.length() == 0 || !eat('(')) { pos = start; throw err("expected function call"); }
        Functions.Signature sig = Functions.signature(name.toString());
        if (sig == null) throw err("unknown function '" + name + "'");
        skipWs();
        List<Ast.Expr> args = new ArrayList<>();
        if (!peek(')')) {
            args.add(parseFunctionArgument());
            while (true) {
                skipWs();
                if (eat(',')) {
                    skipWs();
                    args.add(parseFunctionArgument());
                } else break;
            }
        } else {
            // no args
        }
        skipWs();
        expect(')');
        if (args.size() != sig.params.length)
            throw err("function '" + name + "' expects " + sig.params.length + " argument(s)");
        for (int i = 0; i < args.size(); i++) {
            checkArgType(name.toString(), args.get(i), sig.params[i]);
        }
        return new Ast.FunctionExpr(name.toString(), args, sig.returns);
    }

    /** function-argument = literal / filter-query / logical-expr / function-expr */
    private Ast.Expr parseFunctionArgument() {
        // A logical-expr argument can begin with '!' or '(' or be a composition;
        // parse the widest form and classify afterwards.
        int save = pos;
        char c = src.charAt(pos);
        if (c == '!' || c == '(') {
            Ast.Expr e = parseLogicalOr();
            return e;
        }
        Ast.Expr item = parseItem();
        int after = pos;
        skipWs();
        String op = peekComparisonOp();
        boolean logicalFollows = pos + 1 < src.length()
                && ((src.charAt(pos) == '&' && src.charAt(pos + 1) == '&')
                 || (src.charAt(pos) == '|' && src.charAt(pos + 1) == '|'));
        if (op != null || logicalFollows) {
            pos = save;
            return parseLogicalOr(); // comparison / composite logical argument
        }
        pos = after;
        return item;
    }

    private void checkArgType(String fn, Ast.Expr arg, Functions.Type param) {
        switch (param) {
            case VALUE:
                if (arg instanceof Ast.Literal) return;
                if (arg instanceof Ast.QueryExpr) {
                    if (((Ast.QueryExpr) arg).singular) return;
                    throw err("function '" + fn + "' requires a singular query argument");
                }
                if (arg instanceof Ast.FunctionExpr && ((Ast.FunctionExpr) arg).returnType == Functions.Type.VALUE) return;
                throw err("argument of '" + fn + "' must be a value");
            case NODES:
                if (arg instanceof Ast.QueryExpr) return;
                if (arg instanceof Ast.FunctionExpr && ((Ast.FunctionExpr) arg).returnType == Functions.Type.NODES) return;
                throw err("argument of '" + fn + "' must be a query");
            case LOGICAL:
                if (arg instanceof Ast.Literal) throw err("argument of '" + fn + "' must be a logical expression");
                if (arg instanceof Ast.FunctionExpr && ((Ast.FunctionExpr) arg).returnType == Functions.Type.VALUE)
                    throw err("argument of '" + fn + "' must be a logical expression");
                return;
        }
    }

    /** Comparables: literal, singular query, ValueType function. */
    private void requireComparable(Ast.Expr e) {
        if (e instanceof Ast.Literal) return;
        if (e instanceof Ast.QueryExpr) {
            if (!((Ast.QueryExpr) e).singular) throw err("non-singular query is not comparable");
            return;
        }
        if (e instanceof Ast.FunctionExpr) {
            if (((Ast.FunctionExpr) e).returnType != Functions.Type.VALUE)
                throw err("function result is not comparable");
            return;
        }
        throw err("expression is not comparable");
    }

    /** In logical position (filter root, paren, ! operand): forbid bare ValueType functions handled in parseBasicExpr; here forbid literals reaching logical level. */
    private void requireLogicalContext(Ast.Expr e) {
        if (e instanceof Ast.Literal) throw err("a literal is not a logical expression");
    }

    private static boolean isSingular(List<Ast.Segment> segs) {
        for (Ast.Segment s : segs) {
            if (s.descendant || s.selectors.size() != 1) return false;
            Ast.Selector sel = s.selectors.get(0);
            if (!(sel instanceof Ast.NameSelector) && !(sel instanceof Ast.IndexSelector)) return false;
        }
        return true;
    }

    private String peekComparisonOp() {
        if (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '=' && peekAt(1, '=')) return "==";
            if (c == '!' && peekAt(1, '=')) return "!=";
            if (c == '<') return peekAt(1, '=') ? "<=" : "<";
            if (c == '>') return peekAt(1, '=') ? ">=" : ">";
        }
        return null;
    }

    private boolean matchWord(String word) {
        if (src.startsWith(word, pos)) {
            int end = pos + word.length();
            // must not be followed by a name-char (would be a function name/shorthand-like token)
            if (end < src.length()) {
                int cp = src.codePointAt(end);
                if (isNameChar(cp)) return false;
            }
            pos = end;
            return true;
        }
        return false;
    }

    // ── low-level helpers ────────────────────────────────────────────────
    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
            else break;
        }
    }

    private boolean peek(char c) {
        return pos < src.length() && src.charAt(pos) == c;
    }

    private boolean peekAt(int offset, char c) {
        return pos + offset < src.length() && src.charAt(pos + offset) == c;
    }

    private boolean eat(char c) {
        if (peek(c)) { pos++; return true; }
        return false;
    }

    private void expect(char c) {
        if (!eat(c)) throw err("expected '" + c + "'");
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static <T> List<T> listOf(T item) {
        List<T> l = new ArrayList<>(1);
        l.add(item);
        return l;
    }

    private JsonPathException err(String message) {
        return new JsonPathException(message, pos);
    }
}

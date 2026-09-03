package com.lambdatest.jsonpath;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * RFC 9535 function extensions (length, count, match, search, value) and the
 * I-Regexp (RFC 9485) validator/translator backing match()/search().
 */
final class Functions {
    private Functions() {}

    enum Type { VALUE, LOGICAL, NODES }

    static final class Signature {
        final Type[] params;
        final Type returns;
        Signature(Type returns, Type... params) {
            this.returns = returns; this.params = params;
        }
    }

    static Signature signature(String name) {
        switch (name) {
            case "length": return new Signature(Type.VALUE, Type.VALUE);
            case "count":  return new Signature(Type.VALUE, Type.NODES);
            case "match":  return new Signature(Type.LOGICAL, Type.VALUE, Type.VALUE);
            case "search": return new Signature(Type.LOGICAL, Type.VALUE, Type.VALUE);
            case "value":  return new Signature(Type.VALUE, Type.NODES);
            default: return null;
        }
    }

    /** Sentinel for the absence of a value (the spec's "Nothing"). */
    static final Object NOTHING = new Object() {
        @Override public String toString() { return "<nothing>"; }
    };

    static Object length(Object v) {
        if (v == NOTHING) return NOTHING;
        if (v instanceof String) return (long) ((String) v).codePointCount(0, ((String) v).length());
        if (v instanceof List) return (long) ((List<?>) v).size();
        if (v instanceof Map) return (long) ((Map<?, ?>) v).size();
        return NOTHING;
    }

    static boolean match(Object v, Object regex) {
        Pattern p = compileIRegexp(regex, true);
        return p != null && v instanceof String && p.matcher((String) v).matches();
    }

    static boolean search(Object v, Object regex) {
        Pattern p = compileIRegexp(regex, false);
        return p != null && v instanceof String && p.matcher((String) v).find();
    }

    private static Pattern compileIRegexp(Object regex, boolean anchored) {
        if (!(regex instanceof String)) return null;
        String translated = IRegexp.translate((String) regex);
        if (translated == null) return null;
        try {
            return Pattern.compile(translated);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /**
     * I-Regexp (RFC 9485) validator + translator to java.util.regex.
     * Returns null when the pattern is not valid I-Regexp.
     *
     * Key translations: "." matches everything except \n and \r; "^"/"$" are
     * plain characters (escaped for Java); class atoms are re-escaped so Java
     * extensions (nested classes, intersections) can't activate.
     */
    static final class IRegexp {
        private final String src;
        private int pos;
        private final StringBuilder out = new StringBuilder();

        private IRegexp(String src) { this.src = src; }

        static String translate(String pattern) {
            IRegexp r = new IRegexp(pattern);
            try {
                r.regexp();
                if (r.pos != r.src.length()) return null;
                return r.out.toString();
            } catch (Invalid e) {
                return null;
            }
        }

        private static final class Invalid extends RuntimeException {}

        private void regexp() {
            branch();
            while (pos < src.length() && src.charAt(pos) == '|') {
                out.append('|');
                pos++;
                branch();
            }
        }

        private void branch() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '|' || c == ')') return;
                piece();
            }
        }

        private void piece() {
            atom();
            if (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '*' || c == '+' || c == '?') {
                    out.append(c);
                    pos++;
                } else if (c == '{') {
                    quantifierRange();
                }
            }
        }

        private void quantifierRange() {
            int start = pos;
            pos++; // '{'
            StringBuilder q = new StringBuilder("{");
            if (!digits(q)) throw new Invalid();
            if (pos < src.length() && src.charAt(pos) == ',') {
                q.append(',');
                pos++;
                digits(q); // optional upper bound
            }
            if (pos >= src.length() || src.charAt(pos) != '}') throw new Invalid();
            q.append('}');
            pos++;
            out.append(q);
        }

        private boolean digits(StringBuilder q) {
            boolean any = false;
            while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') {
                q.append(src.charAt(pos++));
                any = true;
            }
            return any;
        }

        private void atom() {
            char c = src.charAt(pos);
            if (c == '(') {
                out.append("(?:");
                pos++;
                regexp();
                if (pos >= src.length() || src.charAt(pos) != ')') throw new Invalid();
                out.append(')');
                pos++;
                return;
            }
            if (c == '.') {
                out.append("[^\\n\\r]");
                pos++;
                return;
            }
            if (c == '[') {
                charClassExpr();
                return;
            }
            if (c == '\\') {
                escapeAtom();
                return;
            }
            if (isNormalChar(c)) {
                // '^' and '$' are passed through as anchors — matches the
                // behavior of the certified reference implementations and the
                // CTS "explicit caret"/"explicit dollar" cases.
                if (c == '^' || c == '$') out.append(c);
                else emitLiteral(c);
                pos++;
                return;
            }
            throw new Invalid();
        }

        /** NormalChar: anything except . \\ ? * + { } ( ) [ ] | — and quantifier context handles { }. */
        private static boolean isNormalChar(char c) {
            switch (c) {
                case '.': case '\\': case '?': case '*': case '+':
                case '{': case '}': case '(': case ')': case '[': case ']': case '|':
                    return false;
                default:
                    return true;
            }
        }

        private void escapeAtom() {
            pos++; // '\\'
            if (pos >= src.length()) throw new Invalid();
            char e = src.charAt(pos);
            if (e == 'p' || e == 'P') {
                category(e);
                return;
            }
            if (isSingleCharEsc(e)) {
                pos++;
                switch (e) {
                    case 'n': out.append("\\n"); return;
                    case 'r': out.append("\\r"); return;
                    case 't': out.append("\\t"); return;
                    default: emitLiteral(e); return;
                }
            }
            throw new Invalid();
        }

        /** SingleCharEsc = "\" ( "(" / ")" / "*" / "+" / "-" / "." / "?" / "[" / "\" / "]" / "^" / "n" / "r" / "t" / "{" / "|" / "}" ) */
        private static boolean isSingleCharEsc(char c) {
            switch (c) {
                case '(': case ')': case '*': case '+': case '-': case '.': case '?':
                case '[': case '\\': case ']': case '^': case 'n': case 'r': case 't':
                case '{': case '|': case '}':
                    return true;
                default:
                    return false;
            }
        }

        private void category(char pOrP) {
            int start = pos;
            pos++; // p/P
            if (pos >= src.length() || src.charAt(pos) != '{') throw new Invalid();
            pos++;
            StringBuilder name = new StringBuilder();
            while (pos < src.length() && src.charAt(pos) != '}') name.append(src.charAt(pos++));
            if (pos >= src.length()) throw new Invalid();
            pos++; // '}'
            if (!isValidCategory(name.toString())) throw new Invalid();
            out.append('\\').append(pOrP).append('{').append(name).append('}');
        }

        private static boolean isValidCategory(String n) {
            switch (n) {
                case "L": case "Lu": case "Ll": case "Lt": case "Lm": case "Lo":
                case "M": case "Mn": case "Mc": case "Me":
                case "N": case "Nd": case "Nl": case "No":
                case "P": case "Pc": case "Pd": case "Ps": case "Pe": case "Pi": case "Pf": case "Po":
                case "Z": case "Zs": case "Zl": case "Zp":
                case "S": case "Sm": case "Sc": case "Sk": case "So":
                case "C": case "Cc": case "Cf": case "Cn": case "Co":
                    return true;
                default:
                    return false;
            }
        }

        private void charClassExpr() {
            pos++; // '['
            out.append('[');
            if (pos < src.length() && src.charAt(pos) == '^') {
                out.append('^');
                pos++;
            }
            if (pos < src.length() && src.charAt(pos) == '-') {
                out.append("\\-");
                pos++;
            }
            boolean any = false;
            while (pos < src.length() && src.charAt(pos) != ']') {
                // trailing '-' just before ']'
                if (src.charAt(pos) == '-' && pos + 1 < src.length() && src.charAt(pos + 1) == ']') {
                    out.append("\\-");
                    pos++;
                    break;
                }
                cce1();
                any = true;
            }
            if (pos >= src.length() || src.charAt(pos) != ']') throw new Invalid();
            if (!any && out.charAt(out.length() - 1) == '[') throw new Invalid(); // empty class
            out.append(']');
            pos++;
        }

        /** CCE1 = ( CCchar [ "-" CCchar ] ) / charClassEsc */
        private void cce1() {
            if (src.charAt(pos) == '\\' && pos + 1 < src.length()
                    && (src.charAt(pos + 1) == 'p' || src.charAt(pos + 1) == 'P')) {
                pos++;
                category(src.charAt(pos));
                return;
            }
            int c1 = ccChar();
            if (pos < src.length() && src.charAt(pos) == '-'
                    && pos + 1 < src.length() && src.charAt(pos + 1) != ']') {
                pos++;
                out.append('-');
                int c2 = ccChar();
                if (c2 < c1) throw new Invalid();
            }
        }

        /** A single class member char (possibly escaped); emits Java-safe form. Returns code point. */
        private int ccChar() {
            char c = src.charAt(pos);
            if (c == '\\') {
                pos++;
                if (pos >= src.length()) throw new Invalid();
                char e = src.charAt(pos);
                if (!isSingleCharEsc(e)) throw new Invalid();
                pos++;
                switch (e) {
                    case 'n': out.append("\\n"); return '\n';
                    case 'r': out.append("\\r"); return '\r';
                    case 't': out.append("\\t"); return '\t';
                    default: emitClassLiteral(e); return e;
                }
            }
            if (c == '-' || c == ']') throw new Invalid();
            int cp = src.codePointAt(pos);
            pos += Character.charCount(cp);
            emitClassLiteralCp(cp);
            return cp;
        }

        private void emitLiteral(char c) {
            emitLiteralCp(c);
        }

        private void emitLiteralCp(int cp) {
            // escape Java regex metacharacters so I-Regexp literals stay literal
            if (cp <= 0x7F && "\\^$.|?*+()[]{}".indexOf(cp) >= 0) out.append('\\');
            out.appendCodePoint(cp);
        }

        private void emitClassLiteral(char c) {
            emitClassLiteralCp(c);
        }

        private void emitClassLiteralCp(int cp) {
            // inside classes Java gives special meaning to [ ] \ ^ - &
            if (cp <= 0x7F && "\\[]^-&".indexOf(cp) >= 0) out.append('\\');
            out.appendCodePoint(cp);
        }
    }
}

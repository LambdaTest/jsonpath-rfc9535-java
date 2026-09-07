package io.github.lambdatest.jsonpath.cts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader for the test harness (keeps the whole repo dependency-free).
 *  Integers -> Long, decimals/exponents -> Double, objects -> insertion-ordered maps. */
public final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) { this.s = s; }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) throw new IllegalArgumentException("trailing JSON at " + p.i);
        return v;
    }

    private Object value() {
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            if (s.charAt(i++) != ':') throw new IllegalArgumentException("expected ':' at " + i);
            ws();
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("expected ',' at " + i);
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw new IllegalArgumentException("expected ',' at " + i);
        }
    }

    private String string() {
        if (s.charAt(i) != '"') throw new IllegalArgumentException("expected string at " + i);
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new IllegalArgumentException("bad escape \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        if (s.charAt(i) == '-') i++;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        boolean dbl = false;
        if (i < s.length() && s.charAt(i) == '.') {
            dbl = true; i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            dbl = true; i++;
            if (s.charAt(i) == '+' || s.charAt(i) == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        }
        String t = s.substring(start, i);
        if (dbl) return Double.parseDouble(t);
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return Double.parseDouble(t);
        }
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) throw new IllegalArgumentException("bad literal at " + i);
        i += word.length();
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }
}

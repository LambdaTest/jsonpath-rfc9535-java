package io.github.lambdatest.jsonpath;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Deep equality and ordering per RFC 9535 comparison semantics. */
final class JsonUtil {
    private JsonUtil() {}

    static boolean isNumber(Object o) {
        return o instanceof Number && !(o instanceof Boolean);
    }

    static BigDecimal toDecimal(Object o) {
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Double || o instanceof Float) return new BigDecimal(((Number) o).doubleValue());
        return BigDecimal.valueOf(((Number) o).longValue());
    }

    /** RFC 9535 "==": same JSON type (numbers cross-type numeric), deep for structures. */
    static boolean deepEquals(Object a, Object b) {
        if (a == null || b == null) return a == null && b == null;
        if (isNumber(a) && isNumber(b)) return toDecimal(a).compareTo(toDecimal(b)) == 0;
        if (a instanceof String && b instanceof String) return a.equals(b);
        if (a instanceof Boolean && b instanceof Boolean) return a.equals(b);
        if (a instanceof List && b instanceof List) {
            List<?> la = (List<?>) a, lb = (List<?>) b;
            if (la.size() != lb.size()) return false;
            for (int i = 0; i < la.size(); i++) {
                if (!deepEquals(la.get(i), lb.get(i))) return false;
            }
            return true;
        }
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> ma = (Map<?, ?>) a, mb = (Map<?, ?>) b;
            if (ma.size() != mb.size()) return false;
            for (Map.Entry<?, ?> e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey())) return false;
                if (!deepEquals(e.getValue(), mb.get(e.getKey()))) return false;
            }
            return true;
        }
        return false;
    }

    /** RFC 9535 "<": defined only for number pairs and string pairs (code point order). */
    static boolean lessThan(Object a, Object b) {
        if (isNumber(a) && isNumber(b)) return toDecimal(a).compareTo(toDecimal(b)) < 0;
        if (a instanceof String && b instanceof String) return codePointCompare((String) a, (String) b) < 0;
        return false;
    }

    private static int codePointCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i), cb = b.codePointAt(j);
            if (ca != cb) return Integer.compare(ca, cb);
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}

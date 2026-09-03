package com.lambdatest.jsonpath.cts;

import com.lambdatest.jsonpath.JsonPath;
import com.lambdatest.jsonpath.JsonPathException;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/** Runs the official RFC 9535 Compliance Test Suite. Usage: CtsRunner <cts.json> [-v] */
public final class CtsRunner {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "../jsonpath-lab/cts/cts.json";
        boolean verbose = args.length > 1 && args[1].equals("-v");
        Map<String, Object> cts = (Map<String, Object>) MiniJson.parse(Files.readString(Paths.get(path)));
        List<Map<String, Object>> tests = (List<Map<String, Object>>) (List<?>) cts.get("tests");

        int passed = 0, failCount = 0;
        StringBuilder failures = new StringBuilder();

        for (Map<String, Object> t : tests) {
            String name = (String) t.get("name");
            String selector = (String) t.get("selector");
            boolean invalid = Boolean.TRUE.equals(t.get("invalid_selector"));

            JsonPath compiled = null;
            String failure = null;
            try {
                compiled = JsonPath.parse(selector);
                if (invalid) failure = "accepted invalid selector";
            } catch (JsonPathException e) {
                if (!invalid) failure = "rejected valid selector: " + e.getMessage();
            } catch (RuntimeException e) {
                failure = (invalid ? null : "crashed on parse: " + e);
            }

            if (failure == null && compiled != null && !invalid) {
                try {
                    List<Object> got = compiled.query(t.get("document"));
                    boolean ok = false;
                    if (t.containsKey("result")) {
                        ok = eq(got, t.get("result"));
                    } else if (t.containsKey("results")) {
                        for (Object alt : (List<Object>) t.get("results")) {
                            if (eq(got, alt)) { ok = true; break; }
                        }
                    }
                    if (!ok) failure = "result mismatch: got=" + render(got) + " expected=" + render(t.getOrDefault("result", t.get("results")));
                } catch (RuntimeException e) {
                    failure = "crashed on eval: " + e;
                }
            }

            if (failure == null) {
                passed++;
            } else {
                failCount++;
                if (failCount <= 40 || verbose) {
                    failures.append("FAIL: ").append(name).append("\n  selector: ").append(selector)
                            .append("\n  ").append(failure).append("\n");
                }
            }
        }

        System.out.println(failures);
        System.out.println("CTS: " + passed + "/" + tests.size() + " passed, " + failCount + " failed");
        if (failCount > 0) System.exit(1);
    }

    private static boolean eq(Object a, Object b) {
        if (a == null || b == null) return a == null && b == null;
        if (a instanceof Number && b instanceof Number && !(a instanceof Boolean) && !(b instanceof Boolean)) {
            return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString())) == 0;
        }
        if (a instanceof List && b instanceof List) {
            List<?> la = (List<?>) a, lb = (List<?>) b;
            if (la.size() != lb.size()) return false;
            for (int i = 0; i < la.size(); i++) {
                if (!eq(la.get(i), lb.get(i))) return false;
            }
            return true;
        }
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> ma = (Map<?, ?>) a, mb = (Map<?, ?>) b;
            if (ma.size() != mb.size()) return false;
            for (Map.Entry<?, ?> e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey()) || !eq(e.getValue(), mb.get(e.getKey()))) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    private static String render(Object o) {
        String s = String.valueOf(o);
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}

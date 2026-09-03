package com.lambdatest.jsonpath.cts;

import com.lambdatest.jsonpath.JsonPath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Differential runner for the LambdaTest Java engine. Usage: DiffRunner <diffDir> */
public final class DiffRunner {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws IOException {
        Path diffDir = Paths.get(args.length > 0 ? args[0] : "../jsonpath-lab/diff");
        List<Map<String, Object>> queries =
                (List<Map<String, Object>>) (List<?>) MiniJson.parse(Files.readString(diffDir.resolve("queries.json")));
        Map<String, Object> docs = new HashMap<>();

        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map<String, Object> q : queries) {
            String id = (String) q.get("id");
            String expr = (String) q.get("query");
            String docName = (String) q.get("doc");
            Object doc = docs.computeIfAbsent(docName, n -> {
                try {
                    return MiniJson.parse(Files.readString(diffDir.resolve("docs").resolve(n)));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            if (!first) out.append(',');
            first = false;
            writeString(out, id);
            out.append(':');
            try {
                List<Object> values = JsonPath.parse(expr).query(doc);
                out.append("{\"ok\":true,\"values\":");
                writeValue(out, values);
                out.append('}');
            } catch (RuntimeException e) {
                out.append("{\"ok\":false,\"error\":");
                writeString(out, String.valueOf(e.getMessage()));
                out.append('}');
            }
        }
        out.append('}');
        Files.writeString(diffDir.resolve("out_java.json"), out.toString());
        System.out.println("java engine done");
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String) { writeString(sb, (String) v); return; }
        if (v instanceof Boolean) { sb.append(v); return; }
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                // canonical-ish: whole doubles as integers (comparator is numeric anyway)
                sb.append((long) d);
            } else {
                sb.append(d);
            }
            return;
        }
        if (v instanceof Number) { sb.append(v); return; }
        if (v instanceof List) {
            sb.append('[');
            List<?> l = (List<?>) v;
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                writeValue(sb, l.get(i));
            }
            sb.append(']');
            return;
        }
        if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, (String) e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        throw new IllegalStateException("unexpected value type " + v.getClass());
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
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
        sb.append('"');
    }
}

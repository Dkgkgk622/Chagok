package org.json;
import java.util.*;
/** TEST-ONLY shim of org.json (android.jar has stubs only). Supports what RouteCodec uses. */
public class JSONObject {
  final LinkedHashMap<String,Object> m = new LinkedHashMap<>();
  public JSONObject put(String k, Object v) { m.put(k, v); return this; }
  public JSONObject put(String k, long v) { m.put(k, v); return this; }
  public JSONObject put(String k, int v) { m.put(k, v); return this; }
  public JSONObject put(String k, double v) { m.put(k, v); return this; }
  public JSONObject put(String k, boolean v) { m.put(k, v); return this; }
  public JSONObject putOpt(String k, Object v) { if (k != null && v != null) m.put(k, v); return this; }
  static String q(Object v) {
    if (v == null) return "null";
    if (v instanceof String) return "\"" + ((String) v).replace("\\","\\\\").replace("\"","\\\"") + "\"";
    if (v instanceof Collection) return new JSONArray((Collection<?>) v).toString();
    return v.toString();
  }
  @Override public String toString() {
    StringBuilder b = new StringBuilder("{"); boolean f = true;
    for (Map.Entry<String,Object> e : m.entrySet()) { if (!f) b.append(','); f = false; b.append(q(e.getKey())).append(':').append(q(e.getValue())); }
    return b.append('}').toString();
  }
}

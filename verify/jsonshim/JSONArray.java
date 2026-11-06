package org.json;
import java.util.*;
/** TEST-ONLY shim. */
public class JSONArray {
  final List<Object> l = new ArrayList<>();
  public JSONArray() {}
  public JSONArray(Collection<?> c) { l.addAll(c); }
  public JSONArray put(Object v) { l.add(v); return this; }
  @Override public String toString() {
    StringBuilder b = new StringBuilder("["); boolean f = true;
    for (Object o : l) { if (!f) b.append(','); f = false; b.append(JSONObject.q(o)); }
    return b.append(']').toString();
  }
}

package plp.api;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

public class PLPResponse
{
  private final LinkedHashMap<String, String> fields = new LinkedHashMap<>();

  public static PLPResponse invalid()
  {
    return new PLPResponse().set("valid", "0");
  }

  public static PLPResponse invalid(String ccrt)
  {
    return invalid().set("ccrt", ccrt);
  }

  public PLPResponse set(String key, String value)
  {
    fields.put(key, value);
    return this;
  }

  public String toJson()
  {
    return new JSONObject(fields).toString();
  }

  public String toXml()
  {
    StringBuilder sb = new StringBuilder("<root>");
    for (Map.Entry<String, String> entry : fields.entrySet())
    {
      sb.append('<').append(entry.getKey()).append('>')
        .append(escapeXml(entry.getValue()))
        .append("</").append(entry.getKey()).append('>');
    }
    sb.append("</root>");
    return sb.toString();
  }

  private static String escapeXml(String value)
  {
    return value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#039;");
  }
}

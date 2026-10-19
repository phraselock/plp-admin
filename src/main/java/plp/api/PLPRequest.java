package plp.api;

import lib.Log;
import io.javalin.http.Context;
import org.json.JSONObject;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic request container for all /api/mqtt/v1/validate/* calls.
 * <p>
 * Fields are accessed by name via constants — no discrete record fields.
 * The full request (body + bearer) can be dumped for logging at any time.
 */
public class PLPRequest
{
  // ── Field name constants ──────────────────────────────────────────────────

  public static final String DEV_ID       = "devId";
  public static final String QRC          = "qrc";
  public static final String DEFAULT_MODE = "defaultMode";
  public static final String OS           = "os";
  public static final String TLS          = "tls";
  public static final String PORT         = "port";
  public static final String PUBPEMB64    = "pubpemb64";
  public static final String OID_EXT      = "oidExt";
  public static final String AS_XML       = "asXML";

  // ── Internal ──────────────────────────────────────────────────────────────

  private static final Pattern BEARER_PATTERN = Pattern.compile("Bearer\\s(\\S+)");

  private final JSONObject json;
  private final String     bearer;
  private final String     path;

  // ── Factory ───────────────────────────────────────────────────────────────

  public static PLPRequest from(Context ctx)
  {
    String body = ctx.body();
    JSONObject json = body.isBlank() ? new JSONObject() : new JSONObject(body);
    String bearer = extractBearer(ctx.header("Authorization")).orElse("");
    return new PLPRequest(json, bearer, ctx.path());
  }

  private PLPRequest(JSONObject json, String bearer, String path)
  {
    this.json   = json;
    this.bearer = bearer;
    this.path   = path;
  }

  // ── Field access ──────────────────────────────────────────────────────────

  /** Returns the value for the given field name, or "" if absent. */
  public String get(String key)
  {
    return json.optString(key, "");
  }

  /** Returns true if the field is present and non-empty. */
  public boolean has(String key)
  {
    return !get(key).isEmpty();
  }

  /** Returns the bearer token (from Authorization header), or "". */
  public String bearer()
  {
    return bearer;
  }

  /** Returns true if the client requested an XML response (asXML=1). */
  public boolean asXml()
  {
    return "1".equals(get(AS_XML));
  }

  /** Returns the request path (e.g. /api/mqtt/v1/validate/clientcert). */
  public String path()
  {
    return path;
  }

  // ── Logging ───────────────────────────────────────────────────────────────

  /**
   * Returns a formatted dump of the full request for logging.
   */
  public String dump()
  {
    JSONObject out = new JSONObject();
    out.put("path",   path);
    out.put("bearer", bearer);
    out.put("body",   json);
    return Log.json(out.toString());
  }

  // ── Internal ──────────────────────────────────────────────────────────────

  private static Optional<String> extractBearer(String authHeader)
  {
    if (authHeader == null) return Optional.empty();
    Matcher m = BEARER_PATTERN.matcher(authHeader);
    return m.find() ? Optional.of(m.group(1)) : Optional.empty();
  }
}

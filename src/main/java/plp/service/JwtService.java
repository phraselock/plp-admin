package plp.service;

import io.jsonwebtoken.Jwts;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.PublicKey;
import java.security.Security;

/**
 * Verifies JWTs signed by plp-core.
 *
 * Usage:
 *   var jwt = new JwtService(token);
 *   jwt.setPublicKey(AppConfig.plCorePublicKey());
 *   if (jwt.isValid()) { String sub = jwt.claim("sub"); ... }
 */
public class JwtService
{
  static { Security.addProvider(new BouncyCastleProvider()); }

  private final String rawToken;
  private PublicKey    publicKey;

  public JwtService(String token)   { this.rawToken = token; }

  public void setPublicKey(PublicKey key) { this.publicKey = key; }

  /** Returns a header value (e.g. "alg", "kid") without verifying the signature. */
  public String header(String name) { return extract(0, name); }

  /** Returns true if the signature is valid and the token has not expired. */
  public boolean isValid()
  {
    if (publicKey == null) return false;
    try
    {
      Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(rawToken);
      return true;
    }
    catch (Exception e) { return false; }
  }

  /**
   * Returns a claim value from the token payload without verifying the signature.
   * Standard names: "sub", "exp", "iat". Returns null if absent or token is malformed.
   */
  public String claim(String claimName)
  {
    return extract(1, claimName);
  }

  private String extract(int segment, String name)
  {
    try
    {
      String[] parts = rawToken.split("\\.");
      if (parts.length < 2) return null;
      String json = new String(java.util.Base64.getUrlDecoder().decode(parts[segment]));

      String pattern = "\"" + name + "\"";
      int idx = json.indexOf(pattern);
      if (idx < 0) return null;
      int colon = json.indexOf(':', idx + pattern.length());
      if (colon < 0) return null;
      int start = colon + 1;
      while (start < json.length() && json.charAt(start) == ' ') start++;
      if (start >= json.length()) return null;

      if (json.charAt(start) == '"')
      {
        int end = json.indexOf('"', start + 1);
        return end > start ? json.substring(start + 1, end) : null;
      }
      else
      {
        int end = start;
        while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(start, end).trim();
      }
    }
    catch (Exception e) { return null; }
  }
}


package plp.lib;

import com.ipoxo.plcore.lib.Log;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Base64;

/**
 * Enrollment tokens: the QR code shown in the admin UI carries one, the phone sends it back
 * to /api/mqtt/v1/validate/smartcard. A token is bound to one issued certificate (serial) and
 * limits the time window in which the phone may fetch it. Only one token per user exists.
 */
public class SmartCardTokenStore
{
  private static final String DB_FILE = "smartcard-users.db";

  private static SmartCardTokenStore instance;

  public static synchronized SmartCardTokenStore getInstance()
  {
    if (instance == null) instance = new SmartCardTokenStore();
    return instance;
  }

  /** What a valid token points to. */
  public record Entry(String username, String serialHex) {}

  private final String dbUrl;

  private SmartCardTokenStore()
  {
    Path dbPath = ConfigPathResolver.resolve(DB_FILE, SmartCardTokenStore.class);
    dbUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(dbUrl);
         Statement stmt = conn.createStatement())
    {
      stmt.execute("""
        CREATE TABLE IF NOT EXISTS smartcard_tokens (
          token      TEXT PRIMARY KEY,
          username   TEXT NOT NULL,
          created_at INTEGER NOT NULL,
          expires_at INTEGER NOT NULL,
          used_at    INTEGER
        )
      """);
    }
    catch (Exception e) { Log.e("[SmartCardTokenStore] init: " + e.getMessage()); }
    try (Connection conn = DriverManager.getConnection(dbUrl); Statement st = conn.createStatement())
    { st.execute("ALTER TABLE smartcard_tokens ADD COLUMN serial_hex TEXT"); }
    catch (SQLException ignored) {}
  }

  /** Creates the token for a certificate (replacing any earlier token of the user). Null on error. */
  public String create(String username, String serialHex, int ttlMinutes)
  {
    byte[] raw = new byte[32];
    new SecureRandom().nextBytes(raw);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    long now = System.currentTimeMillis();
    try (Connection conn = DriverManager.getConnection(dbUrl))
    {
      conn.setAutoCommit(false);
      // one token per user: the newest replaces all older ones; expired tokens of anyone are purged
      try (PreparedStatement del = conn.prepareStatement(
             "DELETE FROM smartcard_tokens WHERE username = ? OR expires_at < ?"))
      {
        del.setString(1, username);
        del.setLong(2, now);
        del.executeUpdate();
      }
      try (PreparedStatement ps = conn.prepareStatement(
             "INSERT INTO smartcard_tokens (token, username, created_at, expires_at, serial_hex) VALUES (?,?,?,?,?)"))
      {
        ps.setString(1, token);
        ps.setString(2, username);
        ps.setLong(3, now);
        ps.setLong(4, now + ttlMinutes * 60_000L);
        ps.setString(5, serialHex);
        ps.executeUpdate();
      }
      conn.commit();
      return token;
    }
    catch (Exception e) { Log.e("[SmartCardTokenStore] create: " + e.getMessage()); }
    return null;
  }

  /**
   * Returns the user and certificate serial the token points to, or null when the token is
   * unknown or expired. Tokens are never consumed; they just expire.
   */
  public Entry resolve(String token)
  {
    try (Connection conn = DriverManager.getConnection(dbUrl);
         PreparedStatement ps = conn.prepareStatement(
           "SELECT username, serial_hex, expires_at FROM smartcard_tokens WHERE token = ?"))
    {
      ps.setString(1, token);
      try (ResultSet rs = ps.executeQuery())
      {
        if (!rs.next()) return null;
        if (rs.getLong("expires_at") < System.currentTimeMillis()) return null;
        String serial = rs.getString("serial_hex");
        if (serial == null) return null;
        return new Entry(rs.getString("username"), serial);
      }
    }
    catch (Exception e) { Log.e("[SmartCardTokenStore] resolve: " + e.getMessage()); }
    return null;
  }
}

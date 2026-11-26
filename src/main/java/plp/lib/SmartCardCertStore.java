package plp.lib;

import com.ipoxo.plcore.lib.Log;

import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Register of issued SmartCard certificates (same SQLite file as SmartCardUserStore).
 * The CRL is derived from the rows with status 'revoked'.
 */
public class SmartCardCertStore
{
  private static final String DB_FILE = "smartcard-users.db";
  private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  public static final String ACTIVE  = "active";
  public static final String REVOKED = "revoked";

  public record Cert(String serialHex, String username, String upn, String issuedAt,
                     String notAfter, String status, String revokedAt, boolean deliverable, String guid,
                     String keyInfo) {}

  /** Private key (PKCS#8 PEM) and certificate (PEM) kept for building the PKCS#12. */
  public record KeyCert(String keyPem, String certPem) {}

  private static SmartCardCertStore instance;

  public static synchronized SmartCardCertStore getInstance()
  {
    if (instance == null) instance = new SmartCardCertStore();
    return instance;
  }

  private final String dbUrl;

  private SmartCardCertStore()
  {
    Path dbPath = ConfigPathResolver.resolve(DB_FILE, SmartCardCertStore.class);
    dbUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(dbUrl);
         Statement stmt = conn.createStatement())
    {
      stmt.execute("""
        CREATE TABLE IF NOT EXISTS smartcard_certs (
          serial_hex TEXT PRIMARY KEY,
          username   TEXT NOT NULL,
          upn        TEXT NOT NULL,
          issued_at  TEXT NOT NULL,
          not_after  TEXT NOT NULL,
          status     TEXT NOT NULL DEFAULT 'active',
          revoked_at TEXT
        )
      """);
    }
    catch (Exception e) { Log.e("[SmartCardCertStore] init: " + e.getMessage()); }
    // migrate: key + certificate stay here so the QR code can be created any number of times
    // (columns p12/p12_password of earlier versions are no longer used)
    for (String col : new String[]{"key_pem TEXT", "cert_pem TEXT", "guid TEXT"})
    {
      try (Connection conn = DriverManager.getConnection(dbUrl); Statement st = conn.createStatement())
      { st.execute("ALTER TABLE smartcard_certs ADD COLUMN " + col); }
      catch (SQLException ignored) {}
    }
  }

  public void add(String serialHex, String username, String upn, java.util.Date notAfter,
                  String keyPem, String certPem, String guid)
  {
    String now = LocalDateTime.now().format(FMT);
    String end = LocalDateTime.ofInstant(notAfter.toInstant(), java.time.ZoneId.systemDefault()).format(FMT);
    try (Connection conn = DriverManager.getConnection(dbUrl);
         PreparedStatement ps = conn.prepareStatement(
           "INSERT INTO smartcard_certs (serial_hex, username, upn, issued_at, not_after, key_pem, cert_pem, guid) VALUES (?,?,?,?,?,?,?,?)"))
    {
      ps.setString(1, serialHex);
      ps.setString(2, username);
      ps.setString(3, upn);
      ps.setString(4, now);
      ps.setString(5, end);
      ps.setString(6, keyPem);
      ps.setString(7, certPem);
      ps.setString(8, guid);
      ps.executeUpdate();
    }
    catch (Exception e) { Log.e("[SmartCardCertStore] add: " + e.getMessage()); }
  }

  public List<Cert> findByUsername(String username)
  {
    return query("WHERE username = ? ORDER BY issued_at DESC", username);
  }

  public List<Cert> findRevoked()
  {
    return query("WHERE status = 'revoked'", null);
  }

  public void setRevoked(String serialHex, boolean revoked)
  {
    try (Connection conn = DriverManager.getConnection(dbUrl);
         PreparedStatement ps = conn.prepareStatement(
           "UPDATE smartcard_certs SET status = ?, revoked_at = ? WHERE serial_hex = ?"))
    {
      ps.setString(1, revoked ? REVOKED : ACTIVE);
      ps.setString(2, revoked ? LocalDateTime.now().format(FMT) : null);
      ps.setString(3, serialHex);
      ps.executeUpdate();
    }
    catch (Exception e) { Log.e("[SmartCardCertStore] setRevoked: " + e.getMessage()); }
  }

  /**
   * Deletes a revoked certificate (row, key and certificate). Only rows with status 'revoked' are deleted. The CRL is derived from
   * these rows, so its entry for the serial disappears with the row: the caller rewrites the CRL afterwards.
   * Returns true if a row was deleted.
   */
  public boolean deleteRevoked(String serialHex)
  {
    try (Connection conn = DriverManager.getConnection(dbUrl);
         PreparedStatement ps = conn.prepareStatement(
           "DELETE FROM smartcard_certs WHERE serial_hex = ? AND status = 'revoked'"))
    {
      ps.setString(1, serialHex);
      return ps.executeUpdate() > 0;
    }
    catch (Exception e) { Log.e("[SmartCardCertStore] deleteRevoked: " + e.getMessage()); return false; }
  }

  /**
   * Removes the stored private key of a certificate; the certificate itself stays for the list
   * and the CRL. Afterwards QR code and P12 are no longer available for it.
   */
  public boolean removeKey(String serialHex)
  {
    try (Connection conn = DriverManager.getConnection(dbUrl);
         PreparedStatement ps = conn.prepareStatement(
           "UPDATE smartcard_certs SET key_pem = NULL WHERE serial_hex = ?"))
    {
      ps.setString(1, serialHex);
      return ps.executeUpdate() > 0;
    }
    catch (Exception e) { Log.e("[SmartCardCertStore] removeKey: " + e.getMessage()); return false; }
  }

  public Cert findBySerial(String serialHex)
  {
    List<Cert> l = query("WHERE serial_hex = ?", serialHex);
    return l.isEmpty() ? null : l.get(0);
  }

  /** Returns the stored key and certificate, or null for certificates issued before they were kept. */
  public KeyCert getKeyCert(String serialHex)
  {
    try (Connection conn = DriverManager.getConnection(dbUrl);
         PreparedStatement ps = conn.prepareStatement(
           "SELECT key_pem, cert_pem FROM smartcard_certs WHERE serial_hex = ?"))
    {
      ps.setString(1, serialHex);
      try (ResultSet rs = ps.executeQuery())
      {
        if (rs.next() && rs.getString(1) != null) return new KeyCert(rs.getString(1), rs.getString(2));
      }
    }
    catch (Exception e) { Log.e("[SmartCardCertStore] getKeyCert: " + e.getMessage()); }
    return null;
  }

  /** Key type and length of the certificate's public key for the admin list, for example "RSA 3072" or "EC 256"; "–" if unknown. */
  private static String keyInfo(String certPem)
  {
    if (certPem == null || certPem.isBlank()) return "–";
    try
    {
      java.security.cert.X509Certificate x = (java.security.cert.X509Certificate)
        java.security.cert.CertificateFactory.getInstance("X.509")
          .generateCertificate(new java.io.ByteArrayInputStream(certPem.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
      java.security.PublicKey k = x.getPublicKey();
      if (k instanceof java.security.interfaces.RSAPublicKey r) return "RSA " + r.getModulus().bitLength();
      if (k instanceof java.security.interfaces.ECPublicKey e) return "EC " + e.getParams().getCurve().getField().getFieldSize();
      return k.getAlgorithm();
    }
    catch (Exception e) { return "–"; }
  }

  private List<Cert> query(String where, String arg)
  {
    List<Cert> list = new ArrayList<>();
    try (Connection conn = DriverManager.getConnection(dbUrl);
         PreparedStatement ps = conn.prepareStatement(
           "SELECT serial_hex, username, upn, issued_at, not_after, status, revoked_at, key_pem IS NOT NULL, guid, cert_pem FROM smartcard_certs " + where))
    {
      if (arg != null) ps.setString(1, arg);
      try (ResultSet rs = ps.executeQuery())
      {
        while (rs.next())
          list.add(new Cert(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8) == 1, rs.getString(9),
                            keyInfo(rs.getString(10))));
      }
    }
    catch (Exception e) { Log.e("[SmartCardCertStore] query: " + e.getMessage()); }
    return list;
  }
}

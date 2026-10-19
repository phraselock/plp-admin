package plp.config;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.math.ec.ECPoint;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Security;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads src/main/resources/application.properties.
 * <p>
 * An external application.properties next to the JAR (or the path given via
 * the "app.config" system property) overrides the bundled defaults.
 */
public class AppConfig
{
  private static final String CONFIG_PARAM = "app.config";
  private static final String PROPERTIES   = "application.properties";

  private static final Properties PROPS;

  static
  {
    Security.addProvider(new BouncyCastleProvider());
    PROPS = load();
  }

  private static Properties load()
  {
    Properties props = new Properties();

    // 1. Bundled defaults from JAR
    try (InputStream in = AppConfig.class.getResourceAsStream("/" + PROPERTIES))
    {
      if (in != null)
      {
        props.load(in);
      }
    }
    catch (IOException e)
    {
      throw new RuntimeException("Failed to load " + PROPERTIES, e);
    }

    // 2. External file overrides defaults
    String sysProp = System.getProperty(CONFIG_PARAM);
    Path external = sysProp != null ? Path.of(sysProp) : Path.of(PROPERTIES);

    if (Files.exists(external))
    {
      try (InputStream in = Files.newInputStream(external))
      {
        props.load(in);
      }
      catch (IOException e)
      {
        throw new RuntimeException("Failed to load " + external.toAbsolutePath(), e);
      }
    }

    return props;
  }

  public static int port()
  {
    return Integer.parseInt(PROPS.getProperty("server.port", "7070"));
  }

  /** IP addresses allowed to call this service. Comma-separated. */
  public static Set<String> allowedIps()
  {
    return Arrays.stream(PROPS.getProperty("server.allowedIps", "127.0.0.1,::1,[0:0:0:0:0:0:0:1]").split(","))
      .map(String::trim)
      .filter(ip -> !ip.isEmpty())
      .collect(Collectors.toSet());
  }

  /**
   * Directory containing the customer-provided CA files
   * ca.mqtt.&lt;port&gt;.pem / ca.mqtt.&lt;port&gt;.key.
   */
  public static String caDirectory()
  {
    return PROPS.getProperty("ca.directory", "./certs/CA");
  }

  // ── plp-core connection ───────────────────────────────────────────────────

  /** JWT used to authenticate requests to plp-core. */
  public static String plCoreJwt()  { return PROPS.getProperty("pl.core.jwt", ""); }

  /** Base URL of the plp-core service. */
  public static String plCoreUrl()  { return PROPS.getProperty("pl.core.url", "http://127.0.0.1:7071"); }

  /** EC P-256 public key of plp-core for verifying JWTs issued by plp-core. */
  public static PublicKey plCorePublicKey()
  {
    try
    {
      ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("P-256");
      ECPoint point = spec.getCurve().createPoint(
        new BigInteger(1, HexFormat.of().parseHex(PROPS.getProperty("pl.core.jwt.ec.pub.x"))),
        new BigInteger(1, HexFormat.of().parseHex(PROPS.getProperty("pl.core.jwt.ec.pub.y"))));
      return KeyFactory.getInstance("EC", "BC").generatePublic(new ECPublicKeySpec(point, spec));
    }
    catch (Exception e) { throw new RuntimeException("Cannot build plp-core public key", e); }
  }

  public static int jettyMaxThreads()
  {
    return Integer.parseInt(PROPS.getProperty("jetty.maxThreads", "10"));
  }

  public static int jettyMinThreads()
  {
    return Integer.parseInt(PROPS.getProperty("jetty.minThreads", "2"));
  }

  // ── Admin UI settings (plp-admin additions) ───────────────────────────────

  public static String adminToken()
  {
    return PROPS.getProperty("admin.token", "").trim();
  }

  public static boolean isKeePassEnabled()
  {
    return Boolean.parseBoolean(PROPS.getProperty("bes.keepass.enabled", "true"));
  }

  public static boolean isPsonoEnabled()
  {
    return Boolean.parseBoolean(PROPS.getProperty("bes.psono.enabled", "false"));
  }

  public static String peerConfigStore()
  {
    return PROPS.getProperty("peer.config.store", "sqlite").trim();
  }
}

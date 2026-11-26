package plp.lib;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Reads smartcard.properties (classpath defaults, overridden by the file next to the JAR). */
public class SmartCardConfig
{
  public static Properties load()
  {
    Properties p = new Properties();
    try (InputStream in = SmartCardConfig.class.getResourceAsStream("/smartcard.properties"))
    {
      if (in != null) p.load(in);
    }
    catch (Exception ignored) {}
    try
    {
      Path ext = ConfigPathResolver.resolve("smartcard.properties", SmartCardConfig.class);
      if (Files.exists(ext))
      {
        try (InputStream in = Files.newInputStream(ext)) { p.load(in); }
      }
    }
    catch (Exception ignored) {}
    return p;
  }

  /** Default validity of an enrollment token in minutes. */
  public static int tokenTtlMinutes()
  {
    try { return Integer.parseInt(load().getProperty("card.token.ttl.minutes", "15").trim()); }
    catch (NumberFormatException e) { return 15; }
  }

  /** card.token.singleuse: true = a QR token works for exactly one successful delivery. */
  public static boolean tokenSingleUse()
  {
    return Boolean.parseBoolean(load().getProperty("card.token.singleuse", "false").trim());
  }

  /**
   * card.key.delete.after.enrollment: true = the card's private key is removed from the database
   * once it has been delivered to the phone; it then exists only in the phone's keystore.
   */
  public static boolean deleteKeyAfterEnrollment()
  {
    return Boolean.parseBoolean(load().getProperty("card.key.delete.after.enrollment", "false").trim());
  }
}

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
}

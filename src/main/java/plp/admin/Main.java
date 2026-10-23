package plp.admin;

import com.ipoxo.plcore.lib.Log;
import plp.config.AppConfig;
import plp.handler.MqttService;
import plp.processor.BESKeePass;
import plp.processor.BESPsono;

/*
  ./gradlew shadowJar
 */

public class Main
{
  public static void main(String[] args) throws Exception
  {
    MqttService mqtt = MqttService.getInstance();

    Runtime.getRuntime().addShutdownHook(new Thread(() ->
    {
      Log.i("[Shutdown] Stopping...");
      Application.stop();
      mqtt.disconnect();
      Log.i("[Shutdown] Done.");
    }, "shutdown"));

    if (AppConfig.isKeePassEnabled())
    {
      Log.i("[Start] Launching KeePass Support");
      BESKeePass.getInstance().initialize(mqtt);
    }

    if (AppConfig.isPsonoEnabled())
    {
      Log.i("[Start] Launching Psono Support");
      BESPsono.getInstance().initialize(mqtt);
    }

    mqtt.connect();

    Application.start();
  }
}

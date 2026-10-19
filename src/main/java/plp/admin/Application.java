package plp.admin;

import com.ipoxo.plcore.lib.Log;
import io.javalin.Javalin;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import plp.api.PLPHandler;
import plp.config.AppConfig;
import plp.crypto.ClientCert;
import plp.crypto.IClientCertIssuer;
import plp.handler.ConfigHandler;
import plp.handler.KeePassHandler;
import plp.handler.SmartCardHandler;

/**
 * Starts a single Javalin instance that serves both the PLP API (port 7070)
 * and the admin UI (/admin/*).
 */
public class Application
{
  private static Javalin web;

  public static void start()
  {
    var threadPool = new QueuedThreadPool(
      AppConfig.jettyMaxThreads(),
      AppConfig.jettyMinThreads(),
      60_000);
    threadPool.setName("jetty");

    IClientCertIssuer certIssuer = new ClientCert(AppConfig.caDirectory());

    web = Javalin.create(config ->
    {
      config.jetty.threadPool = threadPool;

      config.staticFiles.add(sf ->
      {
        sf.hostedPath = "/admin/img";
        sf.directory  = "/public/img";
      });

      config.routes.before(ctx ->
      {
        if (!AppConfig.allowedIps().contains(ctx.ip()))
        {
          ctx.status(403).result("Forbidden");
          ctx.skipRemainingHandlers();
          return;
        }

        if (ctx.path().startsWith("/admin/") && !ctx.path().startsWith("/admin/img/"))
        {
          String token = AppConfig.adminToken();
          if (token.isEmpty() || token.equals(ctx.queryParam("token"))) return;

          ctx.status(403).result("Admin token required");
          ctx.skipRemainingHandlers();
        }
      });

      // ── API routes (from plp-custom) ──────────────────────────────────────
      config.routes.get("/", ctx -> ctx.result("plp-admin is running"));
      new PLPHandler(certIssuer).registerRoutes(config);

      // ── Admin UI routes (from plp-backend) ────────────────────────────────
      new KeePassHandler().registerRoutes(config);
      new SmartCardHandler().registerRoutes(config);
      new ConfigHandler().registerRoutes(config);
    });

    web.start(AppConfig.port());
    Log.i("[Application] Started on port " + AppConfig.port());
  }

  public static void stop()
  {
    if (web != null) web.stop();
  }
}

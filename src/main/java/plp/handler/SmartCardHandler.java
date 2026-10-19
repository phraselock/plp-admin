package plp.handler;

import com.ipoxo.plcore.lib.Log;
import io.javalin.config.JavalinConfig;
import plp.lib.SmartCardUserStore;
import plp.ui.SmartCardPage;

import java.util.List;

public class SmartCardHandler
{
  public void registerRoutes(JavalinConfig config)
  {
    // ── Page ─────────────────────────────────────────────────────────────────

    config.routes.get("/admin/smartcard-user", ctx ->
    {
      List<SmartCardUserStore.User> users = SmartCardUserStore.getInstance().findAll();

      SmartCardUserStore.User selected = null;
      String selectParam = ctx.queryParam("select");
      if (selectParam != null)
      {
        try { selected = SmartCardUserStore.getInstance().findById(Integer.parseInt(selectParam)); }
        catch (NumberFormatException ignored) {}
      }

      ctx.contentType("text/html; charset=UTF-8");
      ctx.result(SmartCardPage.render(users, selected));
    });

    // ── Save (upsert) ─────────────────────────────────────────────────────────

    config.routes.post("/admin/smartcard-user/save", ctx ->
    {
      String name     = ctx.formParam("name");
      String username = ctx.formParam("username");
      String email    = ctx.formParam("email");

      if (name != null && username != null && !username.isBlank())
      {
        int id = SmartCardUserStore.getInstance().upsert(
          name.trim(), username.trim(), email != null ? email.trim() : "");
        Log.i("[SmartCardHandler] User saved: " + username);
        ctx.redirect(adminUrl(ctx, "?select=" + id));
      }
      else
      {
        ctx.redirect(adminUrl(ctx, ""));
      }
    });

    // ── Delete ────────────────────────────────────────────────────────────────

    config.routes.post("/admin/smartcard-user/delete", ctx ->
    {
      String idParam = ctx.formParam("id");
      if (idParam != null)
      {
        try
        {
          SmartCardUserStore.getInstance().deleteById(Integer.parseInt(idParam));
          Log.i("[SmartCardHandler] User deleted: id=" + idParam);
        }
        catch (NumberFormatException ignored) {}
      }
      ctx.redirect(adminUrl(ctx, ""));
    });
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private static String adminUrl(io.javalin.http.Context ctx, String suffix)
  {
    String tok = ctx.queryParam("token");
    String base = "/admin/smartcard-user";
    if (tok == null || tok.isBlank()) return base + suffix;
    String sep = suffix.contains("?") ? "&" : "?";
    return base + suffix + sep + "token=" + tok;
  }
}

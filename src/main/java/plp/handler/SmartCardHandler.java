package plp.handler;

import com.ipoxo.plcore.lib.Log;
import io.javalin.config.JavalinConfig;
import plp.crypto.SmartCardCert;
import plp.lib.ConfigPathResolver;
import plp.lib.SmartCardCertStore;
import plp.lib.SmartCardConfig;
import plp.lib.SmartCardTokenStore;
import plp.lib.SmartCardUserStore;
import plp.ui.SmartCardPage;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

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
      List<SmartCardCertStore.Cert> certs = selected != null
        ? SmartCardCertStore.getInstance().findByUsername(selected.username()) : List.of();
      ctx.result(SmartCardPage.render(users, selected, loadDefaultDomain(), certs,
        SmartCardConfig.tokenTtlMinutes()));
    });

    // ── Save (upsert) ─────────────────────────────────────────────────────────

    config.routes.post("/admin/smartcard-user/save", ctx ->
    {
      String name     = ctx.formParam("name");
      String username = ctx.formParam("username");
      String email    = ctx.formParam("email");
      String domain   = ctx.formParam("domain");

      if (name != null && username != null && !username.isBlank())
      {
        int id = SmartCardUserStore.getInstance().upsert(
          name.trim(), username.trim(),
          email  != null ? email.trim()  : "",
          domain != null ? domain.trim() : "");
        Log.i("[SmartCardHandler] User saved: " + username);
        ctx.redirect(adminUrl(ctx, "?select=" + id));
      }
      else
      {
        ctx.redirect(adminUrl(ctx, ""));
      }
    });

    // ── QR code + JSON for an issued certificate (creates a new token, replacing the user's earlier one) ───────────────

    config.routes.get("/admin/smartcard-user/qr.json", ctx ->
    {
      try
      {
        SmartCardCertStore.Cert cert = SmartCardCertStore.getInstance().findBySerial(ctx.queryParam("serial"));
        if (cert == null || !cert.deliverable() || !SmartCardCertStore.ACTIVE.equals(cert.status()))
        {
          ctx.status(404).result("certificate not found, revoked or no stored key");
          return;
        }
        int ttl = SmartCardConfig.tokenTtlMinutes();
        String ttlParam = ctx.queryParam("ttl");
        if (ttlParam != null && !ttlParam.isBlank()) ttl = Math.max(1, Integer.parseInt(ttlParam.trim()));

        String token = SmartCardTokenStore.getInstance().create(cert.username(), cert.serialHex(), ttl);
        if (token == null) { ctx.status(500).result("token creation failed"); return; }

        JSONObject sc = new JSONObject();
        sc.put("token", token);
        sc.put("upn",   cert.upn());
        JSONObject root = new JSONObject();
        root.put("smartcard", sc);

        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        var matrix = new QRCodeWriter().encode(root.toString(), BarcodeFormat.QR_CODE, 300, 300, hints);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", baos);

        Log.i("[SmartCardHandler] QR token created for cert " + cert.serialHex() + " (" + cert.upn() + "), valid " + ttl + " min");
        JSONObject out = new JSONObject();
        out.put("payload", root);
        out.put("png", java.util.Base64.getEncoder().encodeToString(baos.toByteArray()));
        ctx.contentType("application/json");
        ctx.header("Cache-Control", "no-store");
        ctx.result(out.toString());
      }
      catch (Exception e)
      {
        Log.e("[SmartCardHandler] qr.json: " + e.getMessage());
        ctx.status(500).result(String.valueOf(e.getMessage()));
      }
    });

    // ── Issue a new certificate (explicit action; delivered later via QR code) ──

    config.routes.post("/admin/smartcard-user/cert/issue", ctx ->
    {
      String userId = ctx.formParam("user");
      try
      {
        SmartCardUserStore.User user = SmartCardUserStore.getInstance().findById(Integer.parseInt(userId));
        if (user != null && !user.domain().isBlank())
        {
          SmartCardCert.load().issue(user.username(), user.domain(), user.name());
        }
      }
      catch (Exception e) { Log.e("[SmartCardHandler] issue: " + e.getMessage()); }
      ctx.redirect(adminUrl(ctx, userId != null ? "?select=" + userId : ""));
    });

    // ── PKCS#12 download (password chosen by the admin, nothing is stored) ────

    config.routes.post("/admin/smartcard-user/cert/p12", ctx ->
    {
      try
      {
        String password = ctx.formParam("password");
        SmartCardCertStore.Cert cert = SmartCardCertStore.getInstance().findBySerial(ctx.formParam("serial"));
        SmartCardCertStore.KeyCert kc = cert != null ? SmartCardCertStore.getInstance().getKeyCert(cert.serialHex()) : null;
        if (password == null || password.isEmpty() || kc == null || !SmartCardCertStore.ACTIVE.equals(cert.status()))
        {
          ctx.status(404).result("certificate not found, revoked or no stored key");
          return;
        }
        byte[] p12 = SmartCardCert.load().buildPkcs12(kc.keyPem(), kc.certPem(), password);
        Log.i("[SmartCardHandler] P12 downloaded for " + cert.upn());
        ctx.contentType("application/x-pkcs12");
        ctx.header("Content-Disposition", "attachment; filename=\"" + cert.upn() + ".p12\"");
        ctx.header("Cache-Control", "no-store");
        ctx.result(p12);
      }
      catch (Exception e)
      {
        Log.e("[SmartCardHandler] p12: " + e.getMessage());
        ctx.status(500).result(String.valueOf(e.getMessage()));
      }
    });

    // ── Revoke / reinstate a certificate ──────────────────────────────────────

    config.routes.post("/admin/smartcard-user/cert/revoke",    ctx -> setRevoked(ctx, true));
    config.routes.post("/admin/smartcard-user/cert/reinstate", ctx -> setRevoked(ctx, false));

    // CRL on startup, so the file exists before the first change
    try { SmartCardCert.load().writeCrl(); }
    catch (Exception e) { Log.e("[SmartCardHandler] writeCrl: " + e.getMessage()); }

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

  private static void setRevoked(io.javalin.http.Context ctx, boolean revoked)
  {
    String serial = ctx.formParam("serial");
    String userId = ctx.formParam("user");
    if (serial != null && !serial.isBlank())
    {
      SmartCardCertStore.getInstance().setRevoked(serial, revoked);
      Log.i("[SmartCardHandler] Cert " + serial + (revoked ? " revoked" : " reinstated"));
      try { SmartCardCert.load().writeCrl(); }
      catch (Exception e) { Log.e("[SmartCardHandler] writeCrl: " + e.getMessage()); }
    }
    ctx.redirect(adminUrl(ctx, userId != null ? "?select=" + userId : ""));
  }

  private static String loadDefaultDomain()
  {
    Properties p = new Properties();
    try (InputStream in = SmartCardHandler.class.getResourceAsStream("/smartcard.properties"))
    {
      if (in != null) p.load(in);
    }
    catch (Exception ignored) {}
    try
    {
      Path ext = ConfigPathResolver.resolve("smartcard.properties", SmartCardHandler.class);
      if (Files.exists(ext))
      {
        try (InputStream in = Files.newInputStream(ext)) { p.load(in); }
      }
    }
    catch (Exception ignored) {}
    return p.getProperty("card.domain", "").trim();
  }

  private static String adminUrl(io.javalin.http.Context ctx, String suffix)
  {
    String tok = ctx.queryParam("token");
    String base = "/admin/smartcard-user";
    if (tok == null || tok.isBlank()) return base + suffix;
    String sep = suffix.contains("?") ? "&" : "?";
    return base + suffix + sep + "token=" + tok;
  }
}

package plp.api;

import lib.Log;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import org.json.JSONObject;
import plp.config.AppConfig;
import plp.crypto.IClientCertIssuer;
import plp.crypto.SmartCardCert;
import plp.lib.SmartCardCertStore;
import plp.lib.SmartCardTokenStore;
import plp.lib.SmartCardUserStore;
import plp.service.JwtService;
import plp.service.RestX;

import java.security.PublicKey;
import java.util.*;
import java.util.HexFormat;

public class PLPHandler
{
  private final IClientCertIssuer certIssuer;

  public PLPHandler(IClientCertIssuer certIssuer)
  {
    this.certIssuer = certIssuer;
  }

  public void registerRoutes(JavalinConfig config)
  {
    config.routes.post("/api/mqtt/v1/validate/*", this::resolve);
    config.routes.get("/api/mqtt/v1/validate/*", this::resolve);
  }

  private void resolve(Context ctx) throws Exception
  {
    List<String> segments = Arrays.stream(ctx.path().split("/"))
      .filter(s -> !s.isEmpty())
      .toList();

    int idx = segments.indexOf("validate");
    String type = (idx >= 0 && segments.size() > idx + 1) ? segments.get(idx + 1) : "";

    switch (type)
    {
      case "smartcard"   -> handle(ctx, this::smartCard);
      case "clientcert"   -> handle(ctx, this::clientCert);
      case "clientcertmd" -> handle(ctx, this::clientCertIos);
      case "clientcertios" -> handle(ctx, this::clientCertIos);
      case "clientcertand" -> handle(ctx, this::clientCertAnd);
      case "test"         -> handle(ctx, this::test);
      default             -> ctx.status(500);
    }
  }

  private interface Operation
  {
    PLPResponse run(Context ctx, PLPRequest req) throws Exception;
  }

  private void handle(Context ctx, Operation operation) throws Exception
  {
    ctx.contentType("application/json");

    PLPRequest req = PLPRequest.from(ctx);
    Log.i("[validate] request:\n" + req.dump());

    PLPResponse response = operation.run(ctx, req);

    String responseBody = req.asXml() ? response.toXml() : response.toJson();
    Log.i("[validate] response:\n" + (req.asXml() ? Log.xml(responseBody) : Log.json(responseBody)));
    ctx.result(responseBody);
  }


  private PLPResponse test(Context ctx, PLPRequest req) throws Exception
  {
    return new PLPResponse()
      .set("test", "SUCCESS".equals(ctx.header("X-Client-Verify"))
        ? "test-test with Client Cert"
        : "test-test not Client Cert");
  }

  private boolean verifyPairingData(String devID, String qrc)
  {
    try {
      String coreHost = AppConfig.plCoreUrl();
      String coreJwt = AppConfig.plCoreJwt();

      var jwt = new JwtService(coreJwt);
      PublicKey publKey = AppConfig.plCorePublicKey();
      jwt.setPublicKey(publKey);
      boolean bValid = jwt.isValid();
      if(bValid) {
        Log.i("verifyPairingData: JWT is valid!");
        RestX restX = new RestX(coreHost + "/api/plp/v1/validate/licverify/");

        JSONObject jsonBody = new JSONObject();
        jsonBody.put("devId", devID);
        jsonBody.put("qrc", qrc);

        RestX.Response r = restX.post(jsonBody.toString(), Map.of("Authorization", "Bearer " + coreJwt));

        Log.d("verifyPairingData: http-request status: " + r.status());
        if (r.status() == 200) {
          JSONObject jsonObj = new JSONObject(r.body());
          String valid = jsonObj.optString("valid", "0");
          if (Integer.valueOf(valid) == 1) {
            return true;
          }
        }
      }else{
        Log.i("verifyPairingData: JWT is NOT VALID!");
      }
    } catch (Exception e) {
      Log.e("Exception in verifyPairingData: "+ e.getMessage());
    }
    return false;
  }

  private PLPResponse smartCard(Context ctx, PLPRequest req) throws Exception
  {
    if (!req.has(PLPRequest.DEV_ID) || !req.has(PLPRequest.QRC) || !req.has(PLPRequest.TOKEN))
    {
      Log.d("smartCard: missing required fields");
      return PLPResponse.invalid();
    }

    String ccrt = "0";
    if (!verifyPairingData(req.get(PLPRequest.DEV_ID), req.get(PLPRequest.QRC)))
    {
      Log.d("smartCard: pairing invalid");
      return PLPResponse.invalid(ccrt);
    }

    // the enrollment token (QR code from the admin UI) points to one issued certificate
    String token   = req.get(PLPRequest.TOKEN).trim();
    SmartCardTokenStore.Entry entry = SmartCardTokenStore.getInstance().resolve(token);
    if (entry == null)
    {
      Log.d("smartCard: token unknown or expired");
      return PLPResponse.invalid(ccrt);
    }

    SmartCardCertStore certs = SmartCardCertStore.getInstance();
    SmartCardCertStore.Cert  meta = certs.findBySerial(entry.serialHex());
    SmartCardCertStore.KeyCert kc = certs.getKeyCert(entry.serialHex());
    if (meta == null || kc == null || !SmartCardCertStore.ACTIVE.equals(meta.status()))
    {
      Log.d("smartCard: certificate not available (revoked or no stored key)");
      return PLPResponse.invalid(ccrt);
    }

    // fresh password per request, travels in the response like for the other certificate calls
    String pwd = newPassword();
    byte[] p12 = SmartCardCert.load().buildPkcs12(kc.keyPem(), kc.certPem(), pwd);

    Log.i("[smartCard] Delivered cert for " + meta.upn());
    return new PLPResponse()
      .set("crt",   HexFormat.of().formatHex(p12))
      .set("pwd",   pwd)
      .set("upn",   meta.upn())
      .set("valid", "1")
      .set("ccrt",  ccrt);
  }

  private PLPResponse clientCert(Context ctx, PLPRequest req) throws Exception
  {
    if (!req.has(PLPRequest.DEV_ID) || !req.has(PLPRequest.QRC) || !req.has(PLPRequest.OS)
      || !req.has(PLPRequest.TLS) || !req.has(PLPRequest.PORT))
    {
      Log.d("clientCert: error exit 1");
      return PLPResponse.invalid();
    }

    String ccrt = "0";
    if (!verifyPairingData(req.get(PLPRequest.DEV_ID), req.get(PLPRequest.QRC)))
    {
      Log.d("clientCert: error exit 2");
      return PLPResponse.invalid(ccrt);
    }

    String os = req.get(PLPRequest.OS);
    if (os.equals("win") || os.equals("android"))
    {
      byte[] publicKeyPem = Base64.getDecoder().decode(req.get(PLPRequest.PUBPEMB64));
      IClientCertIssuer.CertResult cert = certIssuer.issueFromPublicKey(publicKeyPem, req.get(PLPRequest.PORT), req.get(PLPRequest.DEFAULT_MODE));
      if (cert != null && !cert.certB64().isEmpty())
      {
        return new PLPResponse()
          .set("serialnr", cert.serialNr())
          .set("certb64", cert.certB64())
          .set("valid", "1")
          .set("ccrt", ccrt);
      }
    }
    else if (os.equals("apple"))
    {
      String pwd = newPassword();
      String pkcs12Hex = certIssuer.issuePkcs12(pwd, req.get(PLPRequest.PORT), req.get(PLPRequest.DEFAULT_MODE));
      if (pkcs12Hex != null && !pkcs12Hex.isEmpty())
      {
        return new PLPResponse()
          .set("crt", pkcs12Hex)
          .set("pwd", pwd)
          .set("valid", "1")
          .set("ccrt", ccrt);
      }
    }

    Log.d("clientCert: error exit 3");
    return PLPResponse.invalid();
  }

  private PLPResponse clientCertIos(Context ctx, PLPRequest req) throws Exception
  {
    if (!req.has(PLPRequest.DEV_ID) || !req.has(PLPRequest.QRC) || !req.has(PLPRequest.OS)
      || !req.has(PLPRequest.TLS) || !req.has(PLPRequest.PORT))
    {
      return PLPResponse.invalid();
    }

    String ccrt = "0";
    if (!verifyPairingData(req.get(PLPRequest.DEV_ID), req.get(PLPRequest.QRC)))
    {
      return PLPResponse.invalid(ccrt);
    }

    if (req.get(PLPRequest.OS).equals("ios"))
    {
      String oidExt = req.has(PLPRequest.OID_EXT) ? req.get(PLPRequest.OID_EXT) : "0";
      String ekuOid = "1.3.6.1.4.1.59269.100.1." + oidExt;

      String pwd = newPassword();
      String pkcs12Hex = certIssuer.issuePkcs12WithEku(pwd, req.get(PLPRequest.PORT), req.get(PLPRequest.DEFAULT_MODE), ekuOid);
      if (pkcs12Hex != null && !pkcs12Hex.isEmpty())
      {
        return new PLPResponse()
          .set("crt", pkcs12Hex)
          .set("pwd", pwd)
          .set("valid", "1")
          .set("ccrt", ccrt);
      }
    }

    return PLPResponse.invalid();
  }

  private PLPResponse clientCertAnd(Context ctx, PLPRequest req) throws Exception
  {
    if (!req.has(PLPRequest.DEV_ID) || !req.has(PLPRequest.QRC) || !req.has(PLPRequest.OS)
      || !req.has(PLPRequest.TLS) || !req.has(PLPRequest.PORT))
    {
      return PLPResponse.invalid();
    }

    String ccrt = "0";
    if (!verifyPairingData(req.get(PLPRequest.DEV_ID), req.get(PLPRequest.QRC)))
    {
      return PLPResponse.invalid(ccrt);
    }

    String os = req.get(PLPRequest.OS);
    if (os.equals("win") || os.equals("android"))
    {
      byte[] publicKeyPem = Base64.getDecoder().decode(req.get(PLPRequest.PUBPEMB64));
      IClientCertIssuer.CertResult cert = certIssuer.issueFromPublicKey(publicKeyPem, req.get(PLPRequest.PORT), req.get(PLPRequest.DEFAULT_MODE));
      if (cert != null && !cert.certB64().isEmpty())
      {
        return new PLPResponse()
          .set("serialnr", cert.serialNr())
          .set("certb64", cert.certB64())
          .set("valid", "1")
          .set("ccrt", ccrt);
      }
    }
    else if (os.equals("apple"))
    {
      String pwd = newPassword();
      String pkcs12Hex = certIssuer.issuePkcs12(pwd, req.get(PLPRequest.PORT), req.get(PLPRequest.DEFAULT_MODE));
      if (pkcs12Hex != null && !pkcs12Hex.isEmpty())
      {
        return new PLPResponse()
          .set("crt", pkcs12Hex)
          .set("pwd", pwd)
          .set("valid", "1")
          .set("ccrt", ccrt);
      }
    }

    return PLPResponse.invalid();
  }

  private static String newPassword()
  {
    return UUID.randomUUID().toString().toUpperCase();
  }
}

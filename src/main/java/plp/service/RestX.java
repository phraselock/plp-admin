package plp.service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;

/**
 * Generic HTTP client for REST API calls.
 * <p>
 * Usage:
 * <pre>
 *   RestX restX = new RestX("https://example.com/api/endpoint");
 *   RestX.Response r = restX.post("{\"key\":\"value\"}", Map.of("Authorization", "Bearer xyz"));
 *   if (r.status() == 200) { ... r.body() ... }
 * </pre>
 */
public class RestX
{
  public record Response(int status, String body) {}

  private final String  url;
  private final boolean skipSslVerify;

  public RestX(String url)
  {
    this(url, false);
  }

  /**
   * @param skipSslVerify mirrors CURLOPT_SSL_VERIFYPEER = false / WinHTTP SECURITY_FLAG_IGNORE_UNKNOWN_CA.
   *                      Only use for self-signed/internal endpoints.
   */
  public RestX(String url, boolean skipSslVerify)
  {
    this.url           = url;
    this.skipSslVerify = skipSslVerify;
  }

  public Response get(Map<String, String> headers) throws Exception
  {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
      .uri(URI.create(url))
      .GET();

    headers.forEach(builder::header);

    return send(builder.build());
  }

  public Response post(String jsonBody, Map<String, String> headers) throws Exception
  {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
      .uri(URI.create(url))
      .header("Content-Type", "application/json")
      .POST(jsonBody == null || jsonBody.isBlank()
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(jsonBody));

    headers.forEach(builder::header);

    return send(builder.build());
  }

  private Response send(HttpRequest request) throws Exception
  {
    HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
    return new Response(response.statusCode(), response.body());
  }

  private HttpClient client() throws Exception
  {
    if (!skipSslVerify)
    {
      return HttpClient.newHttpClient();
    }

    TrustManager[] trustAll = new TrustManager[] { new X509TrustManager()
    {
      public void checkClientTrusted(X509Certificate[] chain, String authType) {}
      public void checkServerTrusted(X509Certificate[] chain, String authType) {}
      public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }};

    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, trustAll, new SecureRandom());

    SSLParameters sslParameters = new SSLParameters();
    sslParameters.setEndpointIdentificationAlgorithm("");

    return HttpClient.newBuilder()
      .sslContext(sslContext)
      .sslParameters(sslParameters)
      .build();
  }
}

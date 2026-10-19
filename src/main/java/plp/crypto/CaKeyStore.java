package plp.crypto;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.io.FileReader;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * The CA the customer provides for signing client certificates, loaded from
 * ca.mqtt_&lt;port&gt;.pem / ca.mqtt_&lt;port&gt;.key (same files used for MQTT mTLS).
 */
public record CaKeyStore(X509Certificate certificate, PrivateKey privateKey)
{
  public static CaKeyStore loadForPort(String caDirectory, String port) throws IOException, CertificateException
  {
    X509Certificate cert = readCertificate(caDirectory + "/ca.mqtt_" + port + ".pem");
    PrivateKey key = readPrivateKey(caDirectory + "/ca.mqtt_" + port + ".key");
    return new CaKeyStore(cert, key);
  }

  private static X509Certificate readCertificate(String path) throws IOException, CertificateException
  {
    try (PEMParser parser = new PEMParser(new FileReader(path)))
    {
      X509CertificateHolder holder = (X509CertificateHolder) parser.readObject();
      return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
    }
  }

  private static PrivateKey readPrivateKey(String path) throws IOException
  {
    try (PEMParser parser = new PEMParser(new FileReader(path)))
    {
      Object obj = parser.readObject();
      if (obj instanceof PEMKeyPair pemKeyPair)
      {
        return new JcaPEMKeyConverter().setProvider("BC").getKeyPair(pemKeyPair).getPrivate();
      }
      throw new IOException("Unsupported CA key format: " + path);
    }
  }
}

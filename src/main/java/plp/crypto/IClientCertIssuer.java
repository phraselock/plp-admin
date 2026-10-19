package plp.crypto;

/**
 * Issues client certificates against the customer-provided CA
 * (ca.mqtt_&lt;port&gt;.pem/.key from the blueprint's certs/CA directory).
 * Implementations replace the make_*.sh / openssl shell scripts.
 */
public interface IClientCertIssuer
{
  record CertResult(String serialNr, String certB64) {}

  /**
   * Port of createPrimeMQTTClientCert(): signs a client certificate for a
   * public key supplied by the device (win/android).
   */
  CertResult issueFromPublicKey(byte[] publicKeyPem, String port, String defaultMode) throws Exception;

  /**
   * Port of createOrgClientCertNoFiles(): generates a fresh key pair, signs
   * a client certificate and returns a PKCS12 (hex-encoded) protected with
   * the given password (apple).
   */
  String issuePkcs12(String password, String port, String defaultMode) throws Exception;

  /**
   * Port of createOrgClientCertNoFilesMD(): like issuePkcs12, but with a
   * dynamic extended-key-usage OID (ios).
   */
  String issuePkcs12WithEku(String password, String port, String defaultMode, String ekuOid) throws Exception;
}

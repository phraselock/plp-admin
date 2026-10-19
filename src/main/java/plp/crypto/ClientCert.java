package plp.crypto;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

/**
 * Issues client certificates with the customer-provided CA, replacing the
 * make_client_cert_from_publpem.sh / make_ecc_client(_md).sh + openssl
 * scripts from the blueprint.
 */
public class ClientCert implements IClientCertIssuer
{
  // matches client_extension_C.cnf / client_extension_D.cnf
  private static final String EKU_OID_C = "1.3.6.1.4.1.59269.100.12";
  private static final String EKU_OID_D = "1.3.6.1.4.1.59269.100.13";

  private static final Duration PUBKEY_CERT_VALIDITY = Duration.ofDays(3650); // 10 years
  private static final Duration ECC_CERT_VALIDITY = Duration.ofDays(7300);    // 20 years

  static
  {
    Security.addProvider(new BouncyCastleProvider());
  }

  private final String caDirectory;

  public ClientCert(String caDirectory)
  {
    this.caDirectory = caDirectory;
  }

  /**
   * Port of createPrimeMQTTClientCert() / make_client_cert_from_publpem.sh:
   * signs a certificate for the device-supplied public key (win/android),
   * extensions per client_extension_C.cnf / client_extension_D.cnf.
   */
  @Override
  public CertResult issueFromPublicKey(byte[] publicKeyPem, String port, String defaultMode) throws Exception
  {
    CaKeyStore ca = CaKeyStore.loadForPort(caDirectory, port);
    PublicKey publicKey = readPublicKey(publicKeyPem);

    String ekuOid = defaultMode.equals("0") ? EKU_OID_C : EKU_OID_D;
    String serialHex = randomSerialHex();

    X509Certificate cert = signCertificate(
      ca,
      new X500Name("CN=com.phraselock.clientcert"),
      publicKey,
      serialHex,
      PUBKEY_CERT_VALIDITY,
      List.of(ekuOid)
    );

    String certB64 = Base64.getEncoder().encodeToString(toPem(cert).getBytes(StandardCharsets.US_ASCII));
    return new CertResult(serialHex, certB64);
  }

  /**
   * Port of createOrgClientCertNoFiles() / make_ecc_client.sh: fresh EC key
   * pair + cert, exported as PKCS12 (apple), extensions per
   * client_extension_C.cnf / client_extension_D.cnf.
   */
  @Override
  public String issuePkcs12(String password, String port, String defaultMode) throws Exception
  {
    String cn = defaultMode.equals("0") ? "com.phraselock.cc.c" : "com.phraselock.cc.d";
    String ekuOid = defaultMode.equals("0") ? EKU_OID_C : EKU_OID_D;
    return issuePkcs12Internal(password, port, cn, List.of(ekuOid));
  }

  /**
   * Port of createOrgClientCertNoFilesMD() / make_ecc_client_md.sh: like
   * issuePkcs12, but for defaultMode != "0" the dynamic ekuOid is used
   * instead of EKU_OID_D, extensions per client_extension_MD.cnf (ios).
   */
  @Override
  public String issuePkcs12WithEku(String password, String port, String defaultMode, String ekuOid) throws Exception
  {
    if (defaultMode.equals("0"))
    {
      return issuePkcs12Internal(password, port, "com.phraselock.cc.c", List.of(EKU_OID_C));
    }
    return issuePkcs12Internal(password, port, "com.phraselock.cc.d", List.of(ekuOid));
  }

  private String issuePkcs12Internal(String password, String port, String cn, List<String> ekuOids) throws Exception
  {
    CaKeyStore ca = CaKeyStore.loadForPort(caDirectory, port);

    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC", "BC");
    keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
    KeyPair keyPair = keyPairGenerator.generateKeyPair();

    String serialHex = randomSerialHex();
    X509Certificate cert = signCertificate(ca, buildSubject(cn), keyPair.getPublic(), serialHex, ECC_CERT_VALIDITY, ekuOids);

    KeyStore pkcs12 = KeyStore.getInstance("PKCS12", "BC");
    pkcs12.load(null, null);
    pkcs12.setKeyEntry("client", keyPair.getPrivate(), password.toCharArray(),
      new X509Certificate[]{cert, ca.certificate()});

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    pkcs12.store(out, password.toCharArray());
    return HexFormat.of().formatHex(out.toByteArray());
  }

  /**
   * Port of: /C=AT/ST=SZG/L=Salzburg/OU=R&D/O=iPoxo IT GmbH/CN=${cn}/emailAddress=info@phraselock.at
   * (client_param in make_ecc_client(_md).sh)
   */
  private static X500Name buildSubject(String cn)
  {
    X500NameBuilder builder = new X500NameBuilder(BCStyle.INSTANCE);
    builder.addRDN(BCStyle.C, "AT");
    builder.addRDN(BCStyle.ST, "SZG");
    builder.addRDN(BCStyle.L, "Salzburg");
    builder.addRDN(BCStyle.OU, "R&D");
    builder.addRDN(BCStyle.O, "iPoxo IT GmbH");
    builder.addRDN(BCStyle.CN, cn);
    builder.addRDN(BCStyle.E, "info@phraselock.at");
    return builder.build();
  }

  /**
   * Port of the "openssl x509 -req -extensions client_cert -extfile ..." step,
   * i.e. signing with the CA and applying the basicConstraints/keyUsage/EKU
   * extensions from the client_extension_*.cnf files.
   */
  private static X509Certificate signCertificate(CaKeyStore ca, X500Name subject, PublicKey publicKey,
                                                   String serialHex, Duration validity, List<String> ekuOids) throws Exception
  {
    Instant now = Instant.now();
    X500Name issuer = new JcaX509CertificateHolder(ca.certificate()).getSubject();

    X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
      issuer,
      new BigInteger(serialHex, 16),
      Date.from(now),
      Date.from(now.plus(validity)),
      subject,
      publicKey
    );

    builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
    builder.addExtension(Extension.keyUsage, false, new KeyUsage(KeyUsage.digitalSignature));

    KeyPurposeId[] purposes = new KeyPurposeId[ekuOids.size() + 1];
    purposes[0] = KeyPurposeId.id_kp_clientAuth;
    for (int i = 0; i < ekuOids.size(); i++)
    {
      purposes[i + 1] = KeyPurposeId.getInstance(new ASN1ObjectIdentifier(ekuOids.get(i)));
    }
    builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(purposes));

    ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(ca.privateKey());
    X509CertificateHolder holder = builder.build(signer);

    return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
  }

  /**
   * Port of "-force_pubkey <(printf "%s" "$pubpem")" in make_client_cert_from_publpem.sh.
   */
  private static PublicKey readPublicKey(byte[] pemBytes) throws Exception
  {
    try (PEMParser parser = new PEMParser(new StringReader(new String(pemBytes, StandardCharsets.US_ASCII))))
    {
      Object obj = parser.readObject();
      if (obj instanceof SubjectPublicKeyInfo info)
      {
        return new JcaPEMKeyConverter().setProvider("BC").getPublicKey(info);
      }
      throw new IllegalArgumentException("Not a public key PEM");
    }
  }

  /**
   * Port of "openssl x509 ... -out /dev/stdout" (PEM output) followed by
   * "openssl base64 -A" in make_client_cert_from_publpem.sh.
   */
  private static String toPem(X509Certificate cert) throws Exception
  {
    StringWriter writer = new StringWriter();
    try (PemWriter pemWriter = new PemWriter(writer))
    {
      pemWriter.writeObject(new PemObject("CERTIFICATE", cert.getEncoded()));
    }
    return writer.toString();
  }

  /**
   * Port of "serial_hex=$(openssl rand -hex 32)".
   */
  private static String randomSerialHex()
  {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}

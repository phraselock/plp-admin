package plp.crypto;

import com.ipoxo.plcore.lib.Log;
import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509v2CRLBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import plp.lib.ConfigPathResolver;
import plp.lib.SmartCardCertStore;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.io.FileWriter;
import java.io.Writer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.Properties;

/**
 * Issues Windows SmartCard logon certificates (PKCS#12).
 * Java port of scripts/smartcard/ca-issue-card.sh.
 *
 * Required extensions (documented in ca-issue-card.sh):
 *   subjectAltName  otherName:1.3.6.1.4.1.311.20.2.3;UTF8:<upn>   (MS-UPN)
 *   extendedKeyUsage clientAuth + 1.3.6.1.4.1.311.20.2.2           (SC Logon)
 *   keyUsage        critical, digitalSignature + keyEncipherment (RSA)
 *                   critical, digitalSignature + keyAgreement    (ECC)
 *   crlDistributionPoints URI:<crl_url>  (when configured)
 */
public class SmartCardCert
{
  private static final String  UPN_OID      = "1.3.6.1.4.1.311.20.2.3";
  private static final String  SC_LOGON_OID = "1.3.6.1.4.1.311.20.2.2";
  private static final Duration VALIDITY    = Duration.ofDays(365 * 2);

  static { Security.addProvider(new BouncyCastleProvider()); }

  public record Result(String serialHex) {}

  private final Path   caCertPath;
  private final Path   caKeyPath;
  private final String certType;    // "rsa" or "ecc"
  private final int    certBits;
  private final String crlUrl;
  private final String crlFile;

  // ── Factory ───────────────────────────────────────────────────────────────

  public static SmartCardCert load()
  {
    Properties p = new Properties();
    try (InputStream in = SmartCardCert.class.getResourceAsStream("/smartcard.properties"))
    {
      if (in != null) p.load(in);
    }
    catch (Exception ignored) {}
    try
    {
      Path ext = ConfigPathResolver.resolve("smartcard.properties", SmartCardCert.class);
      if (Files.exists(ext))
      {
        try (InputStream in = Files.newInputStream(ext)) { p.load(in); }
      }
    }
    catch (Exception ignored) {}
    return new SmartCardCert(p);
  }

  private SmartCardCert(Properties props)
  {
    String caCertStr = props.getProperty("ca.cert", "").trim();
    caCertPath  = ConfigPathResolver.resolve(caCertStr, SmartCardCert.class);
    // key lives next to cert with .key extension
    caKeyPath   = Path.of(caCertPath.toString().replaceAll("\\.crt$", ".key"));
    certType    = props.getProperty("card.cert.type", "rsa").trim().toLowerCase();
    certBits    = Integer.parseInt(props.getProperty("card.cert.bits", "2048").trim());
    crlUrl      = props.getProperty("card.crl.url", "").trim();
    crlFile     = props.getProperty("card.crl.file", "").trim();
  }

  // ── Public API ────────────────────────────────────────────────────────────

  /**
   * Issues a SmartCard certificate for username@domain and stores private key + certificate (PEM)
   * in SmartCardCertStore. The PKCS#12 is built per request by buildPkcs12().
   */
  public Result issue(String username, String domain, String commonName) throws Exception
  {
    String upn = username + "@" + domain;
    Log.i("[SmartCardCert] Issuing for UPN: " + upn);

    CaKeyStore ca = CaKeyStore.load(caCertPath, caKeyPath);
    KeyPair    kp = generateKeyPair();

    String         serialHex = randomSerialHex();
    X509Certificate cert     = signCertificate(ca, new X500Name("CN=" + commonName), kp.getPublic(), serialHex, upn);

    SmartCardCertStore.getInstance().add(serialHex, username, upn, cert.getNotAfter(),
      toPem(new JcaPKCS8Generator(kp.getPrivate(), null)), toPem(cert));
    return new Result(serialHex);
  }

  /** Builds a PKCS#12 (key, certificate, CA certificate) from the stored PEMs. */
  public byte[] buildPkcs12(String keyPem, String certPem, String password) throws Exception
  {
    PrivateKey      key  = readPrivateKey(keyPem);
    X509Certificate cert = (X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509", "BC")
      .generateCertificate(new java.io.ByteArrayInputStream(certPem.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    X509Certificate ca   = CaKeyStore.load(caCertPath, caKeyPath).certificate();

    String alias = new JcaX509CertificateHolder(cert).getSubject()
      .getRDNs(org.bouncycastle.asn1.x500.style.BCStyle.CN)[0].getFirst().getValue().toString();

    KeyStore pkcs12 = KeyStore.getInstance("PKCS12", "BC");
    pkcs12.load(null, null);
    pkcs12.setKeyEntry(alias, key, password.toCharArray(), new X509Certificate[]{cert, ca});
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    pkcs12.store(buf, password.toCharArray());
    return buf.toByteArray();
  }

  private static String toPem(Object o) throws Exception
  {
    java.io.StringWriter sw = new java.io.StringWriter();
    try (JcaPEMWriter w = new JcaPEMWriter(sw)) { w.writeObject(o); }
    return sw.toString();
  }

  private static PrivateKey readPrivateKey(String pem) throws Exception
  {
    try (var parser = new org.bouncycastle.openssl.PEMParser(new java.io.StringReader(pem)))
    {
      Object o = parser.readObject();
      var conv = new org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter().setProvider("BC");
      if (o instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo info) return conv.getPrivateKey(info);
      if (o instanceof org.bouncycastle.openssl.PEMKeyPair kp) return conv.getKeyPair(kp).getPrivate();
      throw new IllegalArgumentException("unsupported key PEM");
    }
  }

  // ── CRL ───────────────────────────────────────────────────────────────────

  /**
   * Rebuilds the CRL from all revoked certificates in SmartCardCertStore and writes it to
   * card.crl.file (DER, for the HTTP distribution point) and card.crl.file + ".pem"
   * (PEM, for pkinit_revoke of the Samba KDC). No-op when card.crl.file is not configured.
   */
  public void writeCrl() throws Exception
  {
    if (crlFile.isBlank()) return;

    CaKeyStore ca  = CaKeyStore.load(caCertPath, caKeyPath);
    Instant    now = Instant.now();
    var builder = new JcaX509v2CRLBuilder(ca.certificate(), Date.from(now));
    builder.setNextUpdate(Date.from(now.plus(Duration.ofDays(3650))));
    builder.addExtension(Extension.cRLNumber, false, new CRLNumber(BigInteger.valueOf(now.getEpochSecond())));

    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    for (SmartCardCertStore.Cert c : SmartCardCertStore.getInstance().findRevoked())
    {
      Date when = Date.from(LocalDateTime.parse(c.revokedAt(), fmt).atZone(ZoneId.systemDefault()).toInstant());
      builder.addCRLEntry(new BigInteger(c.serialHex(), 16), when, CRLReason.privilegeWithdrawn);
    }

    String sigAlg = ca.privateKey().getAlgorithm().startsWith("EC") ? "SHA256withECDSA" : "SHA256withRSA";
    var crl = new org.bouncycastle.cert.jcajce.JcaX509CRLConverter().setProvider("BC")
      .getCRL(builder.build(new JcaContentSignerBuilder(sigAlg).build(ca.privateKey())));

    Path der = ConfigPathResolver.resolve(crlFile, SmartCardCert.class);
    if (der.getParent() != null) Files.createDirectories(der.getParent());
    Files.write(der, crl.getEncoded());
    try (Writer w = new FileWriter(der.toString() + ".pem"); JcaPEMWriter pem = new JcaPEMWriter(w))
    {
      pem.writeObject(crl);
    }
    Log.i("[SmartCardCert] CRL written: " + der + " (" + (crl.getRevokedCertificates() == null ? 0 : crl.getRevokedCertificates().size()) + " revoked)");
  }

  // ── Key generation ────────────────────────────────────────────────────────

  private KeyPair generateKeyPair() throws Exception
  {
    if ("ecc".equals(certType))
    {
      String curve = certBits >= 384 ? "secp384r1" : "secp256r1";
      KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC", "BC");
      kpg.initialize(new ECGenParameterSpec(curve), new SecureRandom());
      return kpg.generateKeyPair();
    }
    KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
    kpg.initialize(certBits, new SecureRandom());
    return kpg.generateKeyPair();
  }

  // ── Certificate signing ───────────────────────────────────────────────────

  private X509Certificate signCertificate(CaKeyStore ca, X500Name subject, PublicKey publicKey,
                                           String serialHex, String upn) throws Exception
  {
    Instant now    = Instant.now();
    X500Name issuer = new JcaX509CertificateHolder(ca.certificate()).getSubject();

    var builder = new JcaX509v3CertificateBuilder(
      issuer, new BigInteger(serialHex, 16),
      Date.from(now), Date.from(now.plus(VALIDITY)),
      subject, publicKey);

    builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));

    int ku = "ecc".equals(certType)
      ? KeyUsage.digitalSignature | KeyUsage.keyAgreement
      : KeyUsage.digitalSignature | KeyUsage.keyEncipherment;
    builder.addExtension(Extension.keyUsage, true, new KeyUsage(ku));

    builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(new KeyPurposeId[]{
      KeyPurposeId.id_kp_clientAuth,
      KeyPurposeId.getInstance(new ASN1ObjectIdentifier(SC_LOGON_OID))
    }));

    // UPN as otherName in SAN
    ASN1Encodable otherName = new DERSequence(new ASN1Encodable[]{
      new ASN1ObjectIdentifier(UPN_OID),
      new DERTaggedObject(true, 0, new DERUTF8String(upn))
    });
    builder.addExtension(Extension.subjectAlternativeName, false,
      new GeneralNames(new GeneralName(GeneralName.otherName, otherName)));

    if (!crlUrl.isBlank())
    {
      DistributionPointName dpn = new DistributionPointName(
        new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, crlUrl)));
      builder.addExtension(Extension.cRLDistributionPoints, false,
        new CRLDistPoint(new DistributionPoint[]{new DistributionPoint(dpn, null, null)}));
    }

    String sigAlg = "ecc".equals(certType) ? "SHA256withECDSA" : "SHA256withRSA";
    var signer = new JcaContentSignerBuilder(sigAlg).build(ca.privateKey());
    return new JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer));
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private static String randomSerialHex()
  {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}

package io.pivotal.security.generator;

import io.pivotal.security.controller.v1.CertificateSecretParameters;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.stereotype.Component;

import static java.util.Collections.reverse;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import javax.security.auth.x500.X500Principal;
import javax.validation.ValidationException;

@Component
public class SignedCertificateGenerator {
  private static final Pattern IP_ADDRESS_PATTERN = Pattern.compile("^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)(/\\d+)?$");
  private static final Pattern BAD_IP_ADDRESS_PATTERN = Pattern.compile("^(\\d+\\.){3}\\d+$");
  private static final Pattern DNS_PATTERN_INCLUDING_LEADING_WILDCARD = Pattern.compile("^(\\*\\.)?(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9\\-]*[a-zA-Z0-9])\\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\\-]*[A-Za-z0-9])$");

  @Autowired
  DateTimeProvider timeProvider;

  @Autowired
  RandomSerialNumberGenerator serialNumberGenerator;

  public X509Certificate getSignedByIssuer(X500Principal issuerDn, PrivateKey issuerKey, KeyPair keyPair,
                              CertificateSecretParameters params) throws Exception {
    return get(asX500Name(issuerDn), issuerKey, keyPair, params);
  }

  public X509Certificate getSelfSigned(KeyPair keyPair, CertificateSecretParameters params) throws Exception {
    return get(params.getDN(), keyPair.getPrivate(), keyPair, params);
  }

  X509Certificate get(X500Name issuerDn, PrivateKey issuerKey, KeyPair keyPair, CertificateSecretParameters
      params) throws Exception {
    Instant now = timeProvider.getNow().toInstant();
    SubjectPublicKeyInfo publicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());
    ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(issuerKey);

    final X509v3CertificateBuilder certificateBuilder = new X509v3CertificateBuilder(
        issuerDn,
        serialNumberGenerator.generate(),
        Date.from(now),
        Date.from(now.plus(Duration.ofDays(params.getDurationDays()))),
        params.getDN(),
        publicKeyInfo
    );

    if (params.getAlternativeNames().size() > 0) {
      certificateBuilder.addExtension(Extension.subjectAlternativeName, false, getAlternativeNames(params));
    }

    X509CertificateHolder holder = certificateBuilder.build(contentSigner);

    return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
  }

  private X500Name asX500Name(X500Principal x500Principal) {
    String name = x500Principal.getName();
    List<String> rdns = splitOnCommaCheckingForEscapedCommas(name);
    reverse(rdns);
    return new X500Name(String.join(",", rdns));
  }

  private List<String> splitOnCommaCheckingForEscapedCommas(String name) {
    List<String> result = new ArrayList<>();

    int i = 0;
    StringBuilder sb = new StringBuilder();
    while (i < name.length()) {
      char c = name.charAt(i);
      int peek = i + 1;
      if (c == '\\' && peek < name.length() && name.charAt(peek) == ',') {
        sb.append(c);
        sb.append(",");
        i++;
      } else if (c == ',') {
        if (sb.length() > 0) {
          result.add(sb.toString());
        }
        sb.setLength(0);
      } else {
        sb.append(c);
      }

      i++;
    }

    if (sb.length() > 0) {
      result.add(sb.toString());
    }

    return result;
  }

  private GeneralNames getAlternativeNames(CertificateSecretParameters params) {
    List<String> alternateNames = params.getAlternativeNames();
    GeneralName[] genNames = new GeneralName[alternateNames.size()];
    for (int i = 0; i < alternateNames.size(); i++) {
      String name = alternateNames.get(i);
      if (IP_ADDRESS_PATTERN.matcher(name).matches()) {
        genNames[i] = new GeneralName(GeneralName.iPAddress, name);
      } else if (BAD_IP_ADDRESS_PATTERN.matcher(name).matches()) {
        throw new ValidationException("error.invalid_alternate_name");
      } else if (DNS_PATTERN_INCLUDING_LEADING_WILDCARD.matcher(name).matches()) {
        genNames[i] = new GeneralName(GeneralName.dNSName, name);
      } else {
        throw new ValidationException("error.invalid_alternate_name");
      }
    }
    return new GeneralNames(genNames);
  }
}
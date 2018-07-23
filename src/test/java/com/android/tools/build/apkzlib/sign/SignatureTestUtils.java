/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.build.apkzlib.sign;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import com.android.tools.build.apkzlib.utils.ApkZLibPair;
import java.lang.reflect.Array;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v1CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Utilities to use signatures in tests. */
public class SignatureTestUtils {

  /**
   * Generates a private key / certificate for pre-18 systems.
   *
   * @return the pair with the private key and certificate
   * @throws Exception failed to generate the signature data
   */
  public static ApkZLibPair<PrivateKey, X509Certificate> generateSignaturePre18() throws Exception {
    return generateSignature("RSA", "SHA1withRSA");
  }

  /**
   * Generates a private key / certificate for post-18 systems.
   *
   * @return the pair with the private key and certificate
   * @throws Exception failed to generate the signature data
   */
  public static ApkZLibPair<PrivateKey, X509Certificate> generateSignaturePos18() throws Exception {
    return generateSignature("EC", "SHA256withECDSA");
  }

  /**
   * Obtains all signature/certificate pairs that are available in the current environment. Any
   * exception thrown will be masked under a runtime exception.
   */
  public static ApkZLibPair<PrivateKey, X509Certificate>[] getAllSigningData() {
    List<ApkZLibPair<PrivateKey, X509Certificate>> signingData = new ArrayList<>();

    try {
      try {
        signingData.add(SignatureTestUtils.generateSignaturePre18());
      } catch (NoSuchAlgorithmException e) {
        // OK, fine, we can't do pre-18 signing. Some platforms do not support the old SHA1withRSA.
      }

      signingData.add(SignatureTestUtils.generateSignaturePos18());
    } catch (Exception e) {
      // Wrap to allow calling in public static final initializer.
      throw new RuntimeException(e);
    }

    @SuppressWarnings("unchecked")
    ApkZLibPair<PrivateKey, X509Certificate>[] array =
        (ApkZLibPair<PrivateKey, X509Certificate>[]) Array.newInstance(ApkZLibPair.class, 0);

    return signingData.toArray(array);
  }

  /** Obtains an API level that is valid to use the given private key. */
  public static int getApiLevelForKey(PrivateKey key) {
    // SHA-1 + RSA is always available. SHA-256 with ECDSA only on API 21+.
    if (key.getAlgorithm().equals("EC")) {
      return 21;
    } else {
      return 10;
    }
  }

  /** Generates another private key / X509 certificate using the same algorithm as the given key. */
  public static ApkZLibPair<PrivateKey, X509Certificate> generateAnother(
      ApkZLibPair<PrivateKey, X509Certificate> signingData) throws Exception {
    return generateSignature(signingData.v1.getAlgorithm(), signingData.v2.getSigAlgName());
  }

  /**
   * Generates a private key / certificate.
   *
   * @param sign the asymmetric cypher, <em>e.g.</em>, {@code RSA}
   * @param full the full signature algorithm name, <em>e.g.</em>, {@code SHA1withRSA}
   * @return the pair with the private key and certificate
   * @throws Exception failed to generate the signature data
   */
  public static ApkZLibPair<PrivateKey, X509Certificate> generateSignature(String sign, String full)
      throws Exception {
    // http://stackoverflow.com/questions/28538785/
    // easy-way-to-generate-a-self-signed-certificate-for-java-security-keystore-using

    KeyPairGenerator generator = null;
    if (sign.equals("RSA")) {
      generator = KeyPairGenerator.getInstance("RSA");
    } else if (sign.equals("EC")) {
      generator = KeyPairGenerator.getInstance("EC");
    } else {
      fail("Algorithm " + sign + " not supported.");
    }

    assertNotNull(generator);
    KeyPair keyPair = generator.generateKeyPair();

    Date notBefore = new Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000);
    Date notAfter = new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000);

    X500Name issuer = new X500Name(new X500Principal("cn=Myself").getName());

    SubjectPublicKeyInfo publicKeyInfo;

    if (keyPair.getPublic() instanceof RSAPublicKey) {
      RSAPublicKey rsaPublicKey = (RSAPublicKey) keyPair.getPublic();
      publicKeyInfo =
          SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(
              new RSAKeyParameters(
                  false, rsaPublicKey.getModulus(), rsaPublicKey.getPublicExponent()));
    } else if (keyPair.getPublic() instanceof ECPublicKey) {
      publicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());
    } else {
      fail();
      publicKeyInfo = null;
    }

    X509v1CertificateBuilder builder =
        new X509v1CertificateBuilder(
            issuer, BigInteger.ONE, notBefore, notAfter, issuer, publicKeyInfo);

    ContentSigner signer =
        new JcaContentSignerBuilder(full)
            .setProvider(new BouncyCastleProvider())
            .build(keyPair.getPrivate());
    X509CertificateHolder holder = builder.build(signer);

    JcaX509CertificateConverter converter =
        new JcaX509CertificateConverter().setProvider(new BouncyCastleProvider());

    return new ApkZLibPair<>(keyPair.getPrivate(), converter.getCertificate(holder));
  }
}

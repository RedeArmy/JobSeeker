package com.usac.jobseeker.app.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Tokens de acceso de verdad, firmados con RSA, para probar el validador de la API como lo ve
 * Clavaris: un par de claves, su JWKS público y tokens que se pueden alterar a propósito (emisor,
 * audiencia, vigencia, `sub`, `scope`, firma y algoritmo).
 */
final class TestTokens {

  /** El emisor y el cliente con los que el contexto de pruebas valida (ver `StubIssuer`). */
  static final String CLIENT_ID = "client-de-prueba";

  private final RSAKey key;

  private TestTokens(RSAKey key) {
    this.key = key;
  }

  static TestTokens withKey(String keyId) {
    try {
      return new TestTokens(new RSAKeyGenerator(2048).keyID(keyId).generate());
    } catch (JOSEException failure) {
      throw new IllegalStateException(failure);
    }
  }

  String keyId() {
    return key.getKeyID();
  }

  /** El JWKS con la clave pública de este par, tal como lo serviría Clavaris. */
  String jwks() {
    return new JWKSet(key.toPublicJWK()).toString();
  }

  /** Un JWKS con varias claves, para probar la rotación. */
  static String jwksOf(TestTokens... pairs) {
    JWKSet set =
        new JWKSet(
            java.util.Arrays.stream(pairs)
                .map(pair -> (com.nimbusds.jose.jwk.JWK) pair.key.toPublicJWK())
                .toList());
    return set.toString();
  }

  private static final String BASE64_URL_ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

  /**
   * El mismo token con el último carácter de la firma cambiado de forma que decodifica a los mismos
   * bytes: una firma RSA-2048 son 256 bytes, y el último carácter base64 aporta 2 bits de datos y 4
   * de relleno, así que se invierte un bit del relleno (el más bajo) y la firma no cambia.
   */
  static String changeOnlyThePaddingBitsOfTheSignature(String token) {
    char last = token.charAt(token.length() - 1);
    char changed = BASE64_URL_ALPHABET.charAt(BASE64_URL_ALPHABET.indexOf(last) ^ 0b000001);
    return token.substring(0, token.length() - 1) + changed;
  }

  Builder token(String issuer, Instant now) {
    return new Builder(this, issuer, now);
  }

  /** Un token válido y las perillas para estropearlo de una en una. */
  static final class Builder {
    private final TestTokens keys;
    private final String issuer;
    private String subject = UUID.randomUUID().toString();
    private List<String> audience = List.of(CLIENT_ID);
    private String scope = "openid profile";
    private boolean withScope = true;
    private Instant issuedAt;
    private Instant expiresAt;
    private Instant notBefore;
    private String signedWithKeyId;
    private RSAKey signer;
    private String issuerOverride;
    private JWSAlgorithm algorithm = JWSAlgorithm.RS256;

    private Builder(TestTokens keys, String issuer, Instant now) {
      this.keys = keys;
      this.issuer = issuer;
      this.issuedAt = now;
      this.expiresAt = now.plusSeconds(300);
      this.signer = keys.key;
    }

    Builder subject(String value) {
      this.subject = value;
      return this;
    }

    Builder audience(String... values) {
      this.audience = List.of(values);
      return this;
    }

    Builder noAudience() {
      this.audience = null;
      return this;
    }

    Builder scope(String value) {
      this.scope = value;
      return this;
    }

    /** Como un ID token: sin `scope`. */
    Builder noScope() {
      this.withScope = false;
      return this;
    }

    /**
     * Un token no puede caducar antes de emitirse: si la caducidad es pasada, la emisión retrocede.
     */
    Builder expiresAt(Instant value) {
      this.expiresAt = value;
      if (!issuedAt.isBefore(value)) {
        this.issuedAt = value.minusSeconds(300);
      }
      return this;
    }

    Builder notBefore(Instant value) {
      this.notBefore = value;
      return this;
    }

    Builder issuer(String value) {
      this.issuerOverride = value;
      return this;
    }

    Builder noSubject() {
      this.subject = null;
      return this;
    }

    /** Firma con otra clave pero declara el `kid` de la legítima. */
    Builder signedBy(TestTokens other) {
      this.signer = other.key;
      this.signedWithKeyId = keys.keyId();
      return this;
    }

    Builder algorithm(JWSAlgorithm value) {
      this.algorithm = value;
      return this;
    }

    private JWTClaimsSet claims() {
      JWTClaimsSet.Builder claims =
          new JWTClaimsSet.Builder()
              .issuer(issuerOverride != null ? issuerOverride : issuer)
              .issueTime(Date.from(issuedAt))
              .expirationTime(Date.from(expiresAt))
              .jwtID(UUID.randomUUID().toString());
      if (subject != null) {
        claims.subject(subject);
      }
      if (audience != null) {
        claims.audience(audience);
      }
      if (withScope) {
        claims.claim("scope", List.of(scope.split(" ")));
      }
      if (notBefore != null) {
        claims.notBeforeTime(Date.from(notBefore));
      }
      return claims.build();
    }

    String build() {
      try {
        String kid = signedWithKeyId != null ? signedWithKeyId : keys.keyId();
        SignedJWT jwt =
            new SignedJWT(
                new JWSHeader.Builder(algorithm).keyID(kid).type(JOSEObjectType.JWT).build(),
                claims());
        jwt.sign(new RSASSASigner(signer));
        return jwt.serialize();
      } catch (JOSEException failure) {
        throw new IllegalStateException(failure);
      }
    }

    /** El ataque de confusión de algoritmo: HS256 usando la clave pública como secreto. */
    String buildWithHmacUsingThePublicKey() {
      try {
        SignedJWT jwt =
            new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(keys.keyId()).build(), claims());
        jwt.sign(new MACSigner(keys.key.toRSAPublicKey().getEncoded()));
        return jwt.serialize();
      } catch (JOSEException failure) {
        throw new IllegalStateException(failure);
      }
    }

    /** Un token sin firma (`alg: none`). */
    String buildUnsigned() {
      return new PlainJWT(claims()).serialize();
    }

    /**
     * El mismo token con un byte de la firma cambiado. Ojo con alterar el último carácter de la
     * firma en base64: solo lleva bits de relleno y decodifica a los mismos bytes, así que seguiría
     * siendo válido (se comprobó con un token real de Clavaris).
     */
    String buildWithOneSignatureByteFlipped() {
      String[] parts = build().split("\\.");
      byte[] signature = java.util.Base64.getUrlDecoder().decode(parts[2]);
      signature[signature.length / 2] ^= 0x01;
      return parts[0]
          + "."
          + parts[1]
          + "."
          + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    /** El mismo token con el cuerpo cambiado y la firma original: debe fallar la firma. */
    String buildTampered(String replacementSubject) {
      String[] parts = build().split("\\.");
      String payload =
          new String(
                  java.util.Base64.getUrlDecoder().decode(parts[1]),
                  java.nio.charset.StandardCharsets.UTF_8)
              .replace(subject, replacementSubject);
      String forged =
          java.util.Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return parts[0] + "." + forged + "." + parts[2];
    }
  }
}

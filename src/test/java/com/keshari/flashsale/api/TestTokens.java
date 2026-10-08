package com.keshari.flashsale.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/** HS256 tokens signed with the local-dev secret used in application.yml. */
public final class TestTokens {

	public static final String SECRET = "local-dev-only-hs256-secret-32b!!";

	private TestTokens() {
	}

	public static String buyer(String subject) {
		return mint(subject, "BUYER");
	}

	public static String admin() {
		return mint("admin", "ADMIN");
	}

	public static String mint(String subject, String role) {
		try {
			Instant now = Instant.now();
			JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(subject).claim("role", role).issueTime(Date.from(now))
					.expirationTime(Date.from(now.plusSeconds(3600))).build();
			SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
			jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
			return jwt.serialize();
		}
		catch (Exception e) {
			throw new IllegalStateException("failed to sign test JWT", e);
		}
	}
}

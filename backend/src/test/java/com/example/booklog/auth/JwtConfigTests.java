package com.example.booklog.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import com.example.booklog.TestEntities;

// JWT の期限の確かめ方。期限切れを TOKEN_EXPIRED として見分けられるようにする（機能定義書 3.2）
class JwtConfigTests {

	private final OAuth2TokenValidator<Jwt> validator = JwtConfig.expiryValidator(TestEntities.fixedClock());

	@Test
	void 期限の前なら通す() {
		assertThat(this.validator.validate(jwtExpiringAt(TestEntities.NOW.plusSeconds(1))).hasErrors()).isFalse();
	}

	@Test
	void 期限のちょうどからは期限切れで時計のずれの猶予はない() {
		assertExpired(this.validator.validate(jwtExpiringAt(TestEntities.NOW)));
		assertExpired(this.validator.validate(jwtExpiringAt(TestEntities.NOW.minusSeconds(1))));
	}

	@Test
	void 期限のないトークンは期限切れとして扱う() {
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject("1").build();
		assertExpired(this.validator.validate(jwt));
	}

	private static void assertExpired(OAuth2TokenValidatorResult result) {
		assertThat(result.getErrors()).singleElement()
			.satisfies((error) -> assertThat(error.getErrorCode()).isEqualTo(JwtConfig.TOKEN_EXPIRED_ERROR));
	}

	private static Jwt jwtExpiringAt(Instant expiresAt) {
		return Jwt.withTokenValue("t")
			.header("alg", "HS256")
			.subject("1")
			.issuedAt(expiresAt.minusSeconds(900))
			.expiresAt(expiresAt)
			.build();
	}

}

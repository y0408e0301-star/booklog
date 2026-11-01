package com.example.booklog.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

// リフレッシュトークンの期限と無効化（BR-9）
class RefreshTokenTests {

	private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");

	private static final Instant EXPIRES = NOW.plus(TokenService.REFRESH_TOKEN_LIFETIME);

	private final RefreshToken token = new RefreshToken(1, "0".repeat(64), EXPIRES, NOW);

	@Test
	void 期限のちょうどで切れる() {
		assertThat(this.token.isExpired(EXPIRES.minusNanos(1))).isFalse();
		assertThat(this.token.isExpired(EXPIRES)).isTrue();
	}

	@Test
	void 無効にした日時は最初の1回だけ残す() {
		assertThat(this.token.isRevoked()).isFalse();
		this.token.revoke(NOW);
		this.token.revoke(NOW.plusSeconds(60));
		assertThat(this.token.isRevoked()).isTrue();
		assertThat(this.token.getRevokedAt()).isEqualTo(NOW);
	}

}

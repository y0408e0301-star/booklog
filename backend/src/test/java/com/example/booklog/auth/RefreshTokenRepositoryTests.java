package com.example.booklog.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import com.example.booklog.RepositoryTest;
import com.example.booklog.user.User;

// リフレッシュトークンの無効化と期限切れの削除（BR-9）
@RepositoryTest
class RefreshTokenRepositoryTests {

	private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");

	@Autowired
	RefreshTokenRepository refreshTokens;

	@Autowired
	TestEntityManager em;

	private long taro;

	private long hanako;

	@BeforeEach
	void setUp() {
		this.taro = this.em.persist(new User("taro_y", "たろう", "hash", NOW)).getId();
		this.hanako = this.em.persist(new User("hanako", "はなこ", "hash", NOW)).getId();
	}

	@Test
	void ハッシュで探す() {
		save(this.taro, "a", NOW.plusSeconds(60));
		assertThat(this.refreshTokens.findByTokenHash(hash("a"))).get()
			.extracting(RefreshToken::getUserId)
			.isEqualTo(this.taro);
		assertThat(this.refreshTokens.findByTokenHashForUpdate(hash("a"))).isPresent();
		assertThat(this.refreshTokens.findByTokenHash(hash("b"))).isEmpty();
	}

	@Test
	void その人のまだ使えるトークンだけを無効にする() {
		RefreshToken active = save(this.taro, "a", NOW.plusSeconds(60));
		RefreshToken revoked = save(this.taro, "b", NOW.plusSeconds(60));
		revoked.revoke(NOW.minusSeconds(10));
		RefreshToken other = save(this.hanako, "c", NOW.plusSeconds(60));
		this.em.flush();

		assertThat(this.refreshTokens.revokeAllByUserId(this.taro, NOW)).isEqualTo(1);
		this.em.clear();

		assertThat(reload(active).getRevokedAt()).isEqualTo(NOW);
		// 前に無効にした日時は変えない
		assertThat(reload(revoked).getRevokedAt()).isEqualTo(NOW.minusSeconds(10));
		assertThat(reload(other).isRevoked()).isFalse();
	}

	@Test
	void 期限がちょうど今かそれより前のトークンを消す() {
		save(this.taro, "past", NOW.minusSeconds(1));
		save(this.taro, "now", NOW);
		save(this.taro, "future", NOW.plusSeconds(1));
		this.em.flush();

		assertThat(this.refreshTokens.deleteExpired(NOW)).isEqualTo(2);
		this.em.clear();

		assertThat(this.refreshTokens.findAll()).extracting("tokenHash").containsExactly(hash("future"));
	}

	private RefreshToken save(long userId, String token, Instant expiresAt) {
		return this.em.persist(new RefreshToken(userId, hash(token), expiresAt, NOW));
	}

	private RefreshToken reload(RefreshToken token) {
		return this.em.find(RefreshToken.class, this.em.getId(token));
	}

	private static String hash(String token) {
		return TokenService.hash(token);
	}

}

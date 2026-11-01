package com.example.booklog.auth;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// リフレッシュトークン（データ設計書 4.9）。トークンそのものは持たず、SHA-256 のハッシュだけを持つ
@Entity
@Table(name = "refresh_tokens")
class RefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private @Nullable Long id;

	@Column(nullable = false, updatable = false)
	private long userId;

	// SHA-256 の 16 進数は必ず 64 文字なので、データベースでは char(64)
	@Column(nullable = false, updatable = false, length = 64)
	@JdbcTypeCode(SqlTypes.CHAR)
	private String tokenHash;

	@Column(nullable = false, updatable = false)
	private Instant expiresAt;

	// 取り直しで新しいものに替えたとき・ログアウトしたときに入れる。
	// 無効にしたものが使われたこと（盗まれた疑い）を見分けるため、無効にしてもすぐには消さない（BR-9）
	private @Nullable Instant revokedAt;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected RefreshToken() {
		this(0, "", Instant.EPOCH, Instant.EPOCH);
	}

	RefreshToken(long userId, String tokenHash, Instant expiresAt, Instant now) {
		this.userId = userId;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
		this.createdAt = now;
	}

	boolean isRevoked() {
		return this.revokedAt != null;
	}

	boolean isExpired(Instant now) {
		return !this.expiresAt.isAfter(now);
	}

	void revoke(Instant now) {
		if (this.revokedAt == null) {
			this.revokedAt = now;
		}
	}

	long getUserId() {
		return this.userId;
	}

	@Nullable Instant getRevokedAt() {
		return this.revokedAt;
	}

}

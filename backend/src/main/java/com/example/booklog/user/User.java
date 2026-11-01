package com.example.booklog.user;

import java.time.Duration;
import java.time.Instant;

import org.jspecify.annotations.Nullable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// ユーザー（データ設計書 4.1）
@Entity
@Table(name = "users")
public class User {

	// ログインの失敗がこの回数に達したら、しばらくログインできなくする（A-7、BR-8）
	static final int MAX_FAILED_LOGINS = 5;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private @Nullable Long id;

	// 登録したときの大文字・小文字のまま持つ。比べるときは小文字にする
	@Column(nullable = false, updatable = false)
	private String username;

	@Column(nullable = false)
	private String displayName;

	@Column(nullable = false)
	private String passwordHash;

	@Column(nullable = false)
	private String bio = "";

	@Column(nullable = false)
	private short failedLoginCount;

	private @Nullable Instant lockedUntil;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	protected User() {
		this("", "", "", Instant.EPOCH);
	}

	public User(String username, String displayName, String passwordHash, Instant now) {
		this.username = username;
		this.displayName = displayName;
		this.passwordHash = passwordHash;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public boolean isLocked(Instant now) {
		return this.lockedUntil != null && this.lockedUntil.isAfter(now);
	}

	// ログインに失敗したときに呼ぶ。5 回目でロックをかけ、ロックが解けたらまた 5 回試せるよう回数を 0 に戻す（BR-8）
	public void recordFailedLogin(Instant now, Duration lockDuration) {
		this.failedLoginCount++;
		if (this.failedLoginCount >= MAX_FAILED_LOGINS) {
			this.lockedUntil = now.plus(lockDuration);
			this.failedLoginCount = 0;
		}
		this.updatedAt = now;
	}

	public void recordSuccessfulLogin(Instant now) {
		if (this.failedLoginCount != 0 || this.lockedUntil != null) {
			this.failedLoginCount = 0;
			this.lockedUntil = null;
			this.updatedAt = now;
		}
	}

	// 表示名と自己紹介を変える（PF-6・PF-7。1-11 で使う）。値は確かめてから渡す
	public void editProfile(String displayName, String bio, Instant now) {
		this.displayName = displayName;
		this.bio = bio;
		this.updatedAt = now;
	}

	public long getId() {
		if (this.id == null) {
			throw new IllegalStateException("まだ保存していないユーザーです");
		}
		return this.id;
	}

	public String getUsername() {
		return this.username;
	}

	public String getDisplayName() {
		return this.displayName;
	}

	public String getBio() {
		return this.bio;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public String getPasswordHash() {
		return this.passwordHash;
	}

	public int getFailedLoginCount() {
		return this.failedLoginCount;
	}

	public @Nullable Instant getLockedUntil() {
		return this.lockedUntil;
	}

}

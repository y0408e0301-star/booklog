package com.example.booklog.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

// ユーザーのログインのロック（A-7、BR-8）・プロフィールの変更（PF-6）
class UserTests {

	private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");

	private static final Duration LOCK = Duration.ofMinutes(15);

	private final User user = new User("taro_y", "たろう", "hash", NOW);

	@Test
	void 登録したときはロックされておらず自己紹介は空() {
		assertThat(this.user.isLocked(NOW)).isFalse();
		assertThat(this.user.getFailedLoginCount()).isZero();
		assertThat(this.user.getBio()).isEmpty();
		assertThat(this.user.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void 保存する前はIDを読めない() {
		assertThatIllegalStateException().isThrownBy(this.user::getId);
	}

	@Test
	void 失敗が4回まではロックしない() {
		for (int i = 1; i <= 4; i++) {
			this.user.recordFailedLogin(NOW, LOCK);
			assertThat(this.user.getFailedLoginCount()).isEqualTo(i);
		}
		assertThat(this.user.isLocked(NOW)).isFalse();
	}

	@Test
	void 失敗5回目で15分ロックし回数を0に戻す() {
		failFiveTimes();
		assertThat(this.user.isLocked(NOW)).isTrue();
		assertThat(this.user.getLockedUntil()).isEqualTo(NOW.plus(LOCK));
		assertThat(this.user.getFailedLoginCount()).isZero();
	}

	@Test
	void ロックは15分ちょうどで解ける() {
		failFiveTimes();
		assertThat(this.user.isLocked(NOW.plus(LOCK).minusNanos(1))).isTrue();
		assertThat(this.user.isLocked(NOW.plus(LOCK))).isFalse();
	}

	@Test
	void ログインに成功すると失敗の回数とロックを消す() {
		this.user.recordFailedLogin(NOW, LOCK);
		this.user.recordFailedLogin(NOW, LOCK);
		this.user.recordSuccessfulLogin(NOW);
		assertThat(this.user.getFailedLoginCount()).isZero();

		failFiveTimes();
		this.user.recordSuccessfulLogin(NOW.plus(LOCK));
		assertThat(this.user.getLockedUntil()).isNull();
	}

	@Test
	void プロフィールを変える() {
		this.user.editProfile("はなこ", "よろしく", NOW);
		assertThat(this.user.getDisplayName()).isEqualTo("はなこ");
		assertThat(this.user.getBio()).isEqualTo("よろしく");
	}

	private void failFiveTimes() {
		for (int i = 0; i < 5; i++) {
			this.user.recordFailedLogin(NOW, LOCK);
		}
	}

}

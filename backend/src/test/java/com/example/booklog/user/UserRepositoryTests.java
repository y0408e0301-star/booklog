package com.example.booklog.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import com.example.booklog.RepositoryTest;

import jakarta.persistence.PersistenceException;

// ユーザー ID は大文字・小文字を区別せずに探す（FN-01 A-2、データ設計書 4.1）
@RepositoryTest
class UserRepositoryTests {

	private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");

	@Autowired
	UserRepository users;

	@Autowired
	TestEntityManager em;

	private User taro;

	@BeforeEach
	void setUp() {
		this.taro = this.em.persistAndFlush(new User("Taro_Y", "たろう", "hash", NOW));
		this.em.clear();
	}

	@Test
	void 大文字小文字を区別せずに探し登録したときの書き方で返す() {
		assertThat(this.users.findByUsername("taro_y")).get().extracting(User::getUsername).isEqualTo("Taro_Y");
		assertThat(this.users.findByUsername("TARO_Y")).isPresent();
		assertThat(this.users.findByUsername("taro")).isEmpty();
	}

	@Test
	void ログイン用の探し方も大文字小文字を区別しない() {
		assertThat(this.users.findByUsernameForUpdate("tArO_y")).get()
			.extracting(User::getId)
			.isEqualTo(this.taro.getId());
	}

	@Test
	void あるかどうかも大文字小文字を区別しない() {
		assertThat(this.users.existsByUsername("TARO_Y")).isTrue();
		assertThat(this.users.existsByUsername("hanako")).isFalse();
	}

	@Test
	void 大文字小文字だけが違うユーザーIDは一意索引で登録できない() {
		assertThatExceptionOfType(PersistenceException.class)
			.isThrownBy(() -> this.em.persistAndFlush(new User("taro_y", "別の人", "hash", NOW)));
	}

	@Test
	void 自己紹介とロックを保存して読み直せる() {
		User user = this.users.findById(this.taro.getId()).orElseThrow();
		user.editProfile("たろう", "1 行目\n2 行目", NOW);
		for (int i = 0; i < 5; i++) {
			user.recordFailedLogin(NOW, Duration.ofMinutes(15));
		}
		this.em.flush();
		this.em.clear();

		User reloaded = this.users.findById(this.taro.getId()).orElseThrow();
		assertThat(reloaded.getBio()).isEqualTo("1 行目\n2 行目");
		assertThat(reloaded.getLockedUntil()).isEqualTo(NOW.plusSeconds(15 * 60));
		assertThat(reloaded.getCreatedAt()).isEqualTo(NOW);
	}

}

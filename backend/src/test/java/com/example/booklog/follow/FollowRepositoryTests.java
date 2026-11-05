package com.example.booklog.follow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.booklog.RepositoryTest;
import com.example.booklog.user.User;

// フォロー（follows）の SQL と制約（FN-07 5 章）
@RepositoryTest
class FollowRepositoryTests {

	private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");

	private static final Instant LAST = Instant.parse("9999-12-31T00:00:00Z");

	@Autowired
	FollowRepository follows;

	@Autowired
	TestEntityManager em;

	@Autowired
	JdbcTemplate jdbc;

	private User taro;

	private User hanako;

	private User jiro;

	@BeforeEach
	void setUp() {
		this.taro = this.em.persist(new User("taro_y", "たろう", "hash", NOW));
		this.hanako = this.em.persist(new User("hanako", "はなこ", "hash", NOW));
		this.jiro = this.em.persist(new User("jiro", "じろう", "hash", NOW));
	}

	// FN-11 の件数と followedByMe。フォローは片方向
	@Test
	void フォロー数を数えフォローしているかを調べる() {
		this.follows.insertIfAbsent(this.taro.getId(), this.hanako.getId());
		this.follows.insertIfAbsent(this.taro.getId(), this.jiro.getId());

		assertThat(this.follows.countByFollowerId(this.taro.getId())).isEqualTo(2);
		assertThat(this.follows.countByFollowerId(this.hanako.getId())).isZero();
		assertThat(this.follows.existsByFollowerIdAndFolloweeId(this.taro.getId(), this.hanako.getId())).isTrue();
		assertThat(this.follows.existsByFollowerIdAndFolloweeId(this.hanako.getId(), this.taro.getId())).isFalse();
	}

	// F-4・5 章の 1：何回フォローしても 1 つ。解除は何回しても何もしない
	@Test
	void 何回フォローしても一つで何回解除しても何もしない() {
		this.follows.insertIfAbsent(this.taro.getId(), this.hanako.getId());
		this.follows.insertIfAbsent(this.taro.getId(), this.hanako.getId());
		assertThat(this.follows.countByFolloweeId(this.hanako.getId())).isOne();

		this.follows.deleteByFollowerIdAndFolloweeId(this.taro.getId(), this.hanako.getId());
		this.follows.deleteByFollowerIdAndFolloweeId(this.taro.getId(), this.hanako.getId());
		assertThat(this.follows.countByFolloweeId(this.hanako.getId())).isZero();
	}

	// F-3・5 章の 1：自分自身へのフォローはデータベースの制約でも防ぐ
	@Test
	void 自分はフォローできない() {
		assertThatThrownBy(() -> this.follows.insertIfAbsent(this.taro.getId(), this.taro.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// 5 章の 3：新しい順、同じ日時ならユーザーの ID の大きい順。続きは最後の行より後ろ
	@Test
	void フォロー中とフォロワーを新しい順に読む() {
		this.follows.insertIfAbsent(this.taro.getId(), this.hanako.getId());
		this.follows.insertIfAbsent(this.taro.getId(), this.jiro.getId());
		this.follows.insertIfAbsent(this.jiro.getId(), this.hanako.getId());
		// はなこへのフォローを古くする。じろうへのフォローとたろうからのフォローはそのまま
		Instant old = Instant.parse("2026-01-01T00:00:00Z");
		this.jdbc.update("UPDATE follows SET created_at = ? WHERE follower_id = ? AND followee_id = ?",
				Timestamp.from(old), this.taro.getId(), this.hanako.getId());

		List<FollowRepository.Row> following = this.follows.findFollowing(this.taro.getId(), LAST, Long.MAX_VALUE,
				Limit.of(21));
		assertThat(following).extracting((row) -> row.user().getUsername()).containsExactly("jiro", "hanako");
		assertThat(this.follows.findFollowing(this.taro.getId(), old, this.hanako.getId() + 1, Limit.of(21)))
			.extracting((row) -> row.user().getUsername())
			.containsExactly("hanako");
		assertThat(this.follows.findFollowing(this.taro.getId(), old, this.hanako.getId(), Limit.of(21))).isEmpty();

		assertThat(this.follows.findFollowers(this.hanako.getId(), LAST, Long.MAX_VALUE, Limit.of(21)))
			.extracting((row) -> row.user().getUsername())
			.containsExactly("jiro", "taro_y");
		assertThat(this.follows.findFolloweeIds(this.taro.getId(), List.of(this.hanako.getId(), this.taro.getId())))
			.containsExactly(this.hanako.getId());
	}

	// TC-07-6（5 章の 4）：フォローした人が退会すると、フォロー数が減り一覧から消える
	@Test
	void 退会するとフォローも消える() {
		this.follows.insertIfAbsent(this.taro.getId(), this.hanako.getId());
		this.follows.insertIfAbsent(this.jiro.getId(), this.hanako.getId());
		this.jdbc.update("DELETE FROM users WHERE id = ?", this.jiro.getId());
		assertThat(this.follows.countByFolloweeId(this.hanako.getId())).isOne();
	}

}

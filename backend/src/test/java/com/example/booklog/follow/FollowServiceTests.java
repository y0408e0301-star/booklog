package com.example.booklog.follow;

import static com.example.booklog.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;

import com.example.booklog.TestEntities;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.common.TimeCursor;
import com.example.booklog.user.User;
import com.example.booklog.user.UserRepository;
import com.example.booklog.user.UserSummary;

// フォローの処理の順番とエラー（FN-07）
@ExtendWith(MockitoExtension.class)
class FollowServiceTests {

	private static final Instant LAST = Instant.parse("9999-12-31T00:00:00Z");

	@Mock
	FollowRepository follows;

	@Mock
	UserRepository users;

	private FollowService service;

	private final User hanako = TestEntities.user(2, "hanako");

	@BeforeEach
	void setUp() {
		this.service = new FollowService(this.follows, this.users);
	}

	@Test
	void フォローすると最新のフォロワー数を返す() {
		when(this.users.findByUsername("hanako")).thenReturn(Optional.of(this.hanako));
		when(this.follows.countByFolloweeId(2)).thenReturn(31L);
		assertThat(this.service.follow(1, "hanako")).isEqualTo(new FollowService.FollowResult(true, 31));
		verify(this.follows).insertIfAbsent(1, 2);
	}

	@Test
	void 解除すると最新のフォロワー数を返す() {
		when(this.users.findByUsername("hanako")).thenReturn(Optional.of(this.hanako));
		when(this.follows.countByFolloweeId(2)).thenReturn(30L);
		assertThat(this.service.unfollow(1, "hanako")).isEqualTo(new FollowService.FollowResult(false, 30));
		verify(this.follows).deleteByFollowerIdAndFolloweeId(1, 2);
	}

	// F-3・TC-07-3
	@Test
	void 自分はフォローできない() {
		when(this.users.findByUsername("hanako")).thenReturn(Optional.of(this.hanako));
		assertApiError(() -> this.service.follow(2, "hanako"), ErrorCode.CANNOT_FOLLOW_SELF);
		verify(this.follows, never()).insertIfAbsent(anyLong(), anyLong());
	}

	@Test
	void いないユーザーは404() {
		when(this.users.findByUsername("nobody")).thenReturn(Optional.empty());
		assertApiError(() -> this.service.follow(1, "nobody"), ErrorCode.USER_NOT_FOUND);
		assertApiError(() -> this.service.unfollow(1, "nobody"), ErrorCode.USER_NOT_FOUND);
		assertApiError(() -> this.service.following(1, "nobody", null), ErrorCode.USER_NOT_FOUND);
		assertApiError(() -> this.service.followers(1, "nobody", null), ErrorCode.USER_NOT_FOUND);
	}

	@Test
	void 確かめた後で相手が退会したら404() {
		when(this.users.findByUsername("hanako")).thenReturn(Optional.of(this.hanako));
		doThrow(new DataIntegrityViolationException("fk")).when(this.follows).insertIfAbsent(1, 2);
		assertApiError(() -> this.service.follow(1, "hanako"), ErrorCode.USER_NOT_FOUND);
	}

	// TC-07-4：21 人あれば 20 人と続きの位置。見ている人がフォローしているかと、見ている人自身か
	@Test
	void 二十人ずつ読み続きの位置を返す() {
		when(this.users.findByUsername("hanako")).thenReturn(Optional.of(this.hanako));
		List<FollowRepository.Row> found = LongStream.rangeClosed(1, 21).mapToObj(FollowServiceTests::row).toList();
		when(this.follows.findFollowers(2, LAST, Long.MAX_VALUE, Limit.of(21))).thenReturn(found);
		when(this.follows.findFolloweeIds(1, LongStream.rangeClosed(1, 20).boxed().toList())).thenReturn(List.of(3L));

		FollowService.UserPage page = this.service.followers(1, "hanako", null);
		assertThat(page.items()).hasSize(20);
		assertThat(page.items().getFirst()).isEqualTo(new UserSummary("user1", "user1の表示名", "", false, true));
		assertThat(page.items().get(2).followedByMe()).isTrue();
		assertThat(page.nextCursor()).isEqualTo(new TimeCursor(found.get(19).followedAt(), 20).format());
	}

	@Test
	void 最後まで読めば続きの位置はnullで空なら調べない() {
		when(this.users.findByUsername("hanako")).thenReturn(Optional.of(this.hanako));
		TimeCursor after = new TimeCursor(Instant.parse("2026-10-24T03:00:00Z"), 5);
		when(this.follows.findFollowing(2, after.at(), 5, Limit.of(21))).thenReturn(List.of());

		FollowService.UserPage page = this.service.following(1, "hanako", after.format());
		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isNull();
		verify(this.follows, never()).findFolloweeIds(anyLong(), anyCollection());
	}

	@Test
	void 続きの位置が違えば400() {
		assertApiError(() -> this.service.following(1, "hanako", "x"), ErrorCode.BAD_REQUEST, "cursor");
	}

	private static FollowRepository.Row row(long id) {
		return new FollowRepository.Row(TestEntities.user(id, "user" + id),
				Instant.parse("2026-10-24T03:00:00Z").minusSeconds(id));
	}

}

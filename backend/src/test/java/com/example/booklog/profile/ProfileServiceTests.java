package com.example.booklog.profile;

import static com.example.booklog.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.booklog.TestEntities;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.follow.FollowRepository;
import com.example.booklog.review.ReviewRepository;
import com.example.booklog.shelf.BookshelfCounts;
import com.example.booklog.shelf.ReadingStatuses;
import com.example.booklog.user.User;
import com.example.booklog.user.UserRepository;

// プロフィールの表示と変更の処理の順番とエラー（FN-11）
@ExtendWith(MockitoExtension.class)
class ProfileServiceTests {

	@Mock
	UserRepository users;

	@Mock
	ReviewRepository reviews;

	@Mock
	FollowRepository follows;

	@Mock
	ReadingStatuses statuses;

	private final User taro = TestEntities.user(1, "taro_y");

	private final User hanako = TestEntities.user(2, "hanako");

	private ProfileService service;

	@BeforeEach
	void setUp() {
		this.service = new ProfileService(this.users, this.reviews, this.follows, this.statuses,
				TestEntities.fixedClock());
	}

	@Test
	void ほかの人のプロフィールは件数とフォローしているかを返す() {
		when(this.users.findByUsername("hanako")).thenReturn(Optional.of(this.hanako));
		counts(2);
		when(this.follows.existsByFollowerIdAndFolloweeId(1, 2)).thenReturn(true);

		assertThat(this.service.get(1, "hanako")).isEqualTo(
				new ProfileView("hanako", "hanakoの表示名", "", 12, 8, 30, new BookshelfCounts(5, 2, 14), true, false));
	}

	@Test
	void 自分のプロフィールはmeで自分のフォローは調べない() {
		when(this.users.findByUsername("taro_y")).thenReturn(Optional.of(this.taro));
		counts(1);

		ProfileView view = this.service.get(1, "taro_y");
		assertThat(view.me()).isTrue();
		assertThat(view.followedByMe()).isFalse();
	}

	// TC-11-5
	@Test
	void いないユーザーはUSER_NOT_FOUND() {
		when(this.users.findByUsername("nobody")).thenReturn(Optional.empty());
		assertApiError(() -> this.service.get(1, "nobody"), ErrorCode.USER_NOT_FOUND);
	}

	@Test
	void 変えるとトリムした値を保存して返す() {
		when(this.users.findById(1L)).thenReturn(Optional.of(this.taro));
		counts(1);

		ProfileView view = this.service.edit(1, " たろう ", "一行目\r\n二行目 ");
		assertThat(view.displayName()).isEqualTo("たろう");
		assertThat(view.bio()).isEqualTo("一行目\n二行目");
		assertThat(this.taro.getDisplayName()).isEqualTo("たろう");
		assertThat(this.taro.getBio()).isEqualTo("一行目\n二行目");
		verify(this.statuses).counts(1);
	}

	// TC-11-3
	@Test
	void 条件に合わなければ何も読まない() {
		assertApiError(() -> this.service.edit(1, " ", ""), ErrorCode.DISPLAY_NAME_REQUIRED, "displayName");
		assertApiError(() -> this.service.edit(1, "あ".repeat(31), ""), ErrorCode.BAD_REQUEST, "displayName");
		assertApiError(() -> this.service.edit(1, "たろう", "あ".repeat(161)), ErrorCode.BAD_REQUEST, "bio");
		verifyNoInteractions(this.users);
	}

	@Test
	void 退会した人が変えようとしたらUNAUTHENTICATED() {
		when(this.users.findById(1L)).thenReturn(Optional.empty());
		assertApiError(() -> this.service.edit(1, "たろう", ""), ErrorCode.UNAUTHENTICATED);
	}

	private void counts(long userId) {
		when(this.reviews.countByAuthorId(userId)).thenReturn(12L);
		when(this.follows.countByFollowerId(userId)).thenReturn(8L);
		when(this.follows.countByFolloweeId(userId)).thenReturn(30L);
		when(this.statuses.counts(userId)).thenReturn(new BookshelfCounts(5, 2, 14));
	}

}

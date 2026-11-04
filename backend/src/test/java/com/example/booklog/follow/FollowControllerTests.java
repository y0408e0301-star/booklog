package com.example.booklog.follow;

import static com.example.booklog.ControllerTestSupport.assertError;
import static com.example.booklog.ControllerTestSupport.loggedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.example.booklog.ControllerTest;
import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.user.UserSummary;

// フォローの API の入口（FN-07 6 章）：URL・ステータス・JSON の形。処理の中身は FollowServiceTests
@ControllerTest(FollowController.class)
class FollowControllerTests {

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	FollowService followService;

	@Test
	void フォローするとフォロー中と数を返す() {
		when(this.followService.follow(1, "hanako")).thenReturn(new FollowService.FollowResult(true, 31));
		assertThat(this.mvc.put().uri("/api/users/hanako/follow").with(loggedIn(1)).with(csrf())).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("{\"following\":true,\"followerCount\":31}");
	}

	@Test
	void 解除するとフォローしていない状態と数を返す() {
		when(this.followService.unfollow(1, "hanako")).thenReturn(new FollowService.FollowResult(false, 30));
		assertThat(this.mvc.delete().uri("/api/users/hanako/follow").with(loggedIn(1)).with(csrf())).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("{\"following\":false,\"followerCount\":30}");
	}

	@Test
	void ログインとCSRFトークンが要る() {
		assertThat(this.mvc.put().uri("/api/users/hanako/follow").with(csrf())).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(this.mvc.delete().uri("/api/users/hanako/follow").with(loggedIn(1))).hasStatus(HttpStatus.FORBIDDEN);
		verifyNoInteractions(this.followService);
	}

	// TC-07-3
	@Test
	void 自分は400() {
		when(this.followService.follow(1, "taro_y")).thenThrow(new ApiException(ErrorCode.CANNOT_FOLLOW_SELF));
		assertError(this.mvc.put().uri("/api/users/taro_y/follow").with(loggedIn(1)).with(csrf()).exchange(),
				ErrorCode.CANNOT_FOLLOW_SELF);
	}

	@Test
	void 一覧を返す() {
		UserSummary hanako = new UserSummary("hanako", "はなこ", "ミステリーをよく読みます", true, false);
		when(this.followService.following(1, "taro_y", "1_2"))
			.thenReturn(new FollowService.UserPage(List.of(hanako), null));
		when(this.followService.followers(1, "taro_y", null)).thenReturn(new FollowService.UserPage(List.of(), null));

		assertThat(this.mvc.get().uri("/api/users/taro_y/following?cursor=1_2").with(loggedIn(1))).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("""
					{"items":[{"username":"hanako","displayName":"はなこ","bioFirstLine":"ミステリーをよく読みます",
					  "followedByMe":true,"me":false}],"nextCursor":null}
					""");
		assertThat(this.mvc.get().uri("/api/users/taro_y/followers").with(loggedIn(1))).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("{\"items\":[],\"nextCursor\":null}");
	}

	// TC-07-5
	@Test
	void いないユーザーは404() {
		when(this.followService.following(1, "nobody", null)).thenThrow(new ApiException(ErrorCode.USER_NOT_FOUND));
		assertError(this.mvc.get().uri("/api/users/nobody/following").with(loggedIn(1)).exchange(),
				ErrorCode.USER_NOT_FOUND);
	}

}

package com.example.booklog.profile;

import static com.example.booklog.ControllerTestSupport.assertError;
import static com.example.booklog.ControllerTestSupport.loggedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.example.booklog.ControllerTest;
import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.shelf.BookshelfCounts;

// プロフィールの API の入口（FN-11 6 章）：URL・ステータス・JSON の形。処理の中身は ProfileServiceTests
@ControllerTest(ProfileController.class)
class ProfileControllerTests {

	private static final ProfileView VIEW = new ProfileView("taro_y", "たろう", "ミステリーをよく読みます", 12, 8, 30,
			new BookshelfCounts(5, 2, 14), false, false);

	private static final String VIEW_JSON = """
			{"username":"taro_y","displayName":"たろう","bio":"ミステリーをよく読みます","reviewCount":12,
			 "followingCount":8,"followerCount":30,"bookshelfCounts":{"WANT":5,"READING":2,"READ":14},
			 "followedByMe":false,"me":false}
			""";

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	ProfileService profileService;

	@Test
	void プロフィールを返す() {
		when(this.profileService.get(1, "taro_y")).thenReturn(VIEW);
		assertThat(this.mvc.get().uri("/api/users/taro_y").with(loggedIn(1))).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo(VIEW_JSON);
	}

	@Test
	void いないユーザーは404() {
		when(this.profileService.get(1, "nobody")).thenThrow(new ApiException(ErrorCode.USER_NOT_FOUND));
		assertError(this.mvc.get().uri("/api/users/nobody").with(loggedIn(1)).exchange(), ErrorCode.USER_NOT_FOUND);
	}

	@Test
	void 変えるとプロフィールを返す() {
		when(this.profileService.edit(1, "たろう", "ミステリーをよく読みます")).thenReturn(VIEW);
		assertThat(this.mvc.patch()
			.uri("/api/me/profile")
			.with(loggedIn(1))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"displayName\":\"たろう\",\"bio\":\"ミステリーをよく読みます\"}")).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo(VIEW_JSON);
	}

	@Test
	void 表示名が空なら400() {
		when(this.profileService.edit(1, "", ""))
			.thenThrow(new ApiException(ErrorCode.DISPLAY_NAME_REQUIRED, "displayName"));
		assertError(this.mvc.patch()
			.uri("/api/me/profile")
			.with(loggedIn(1))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"displayName\":\"\",\"bio\":\"\"}")
			.exchange(), ErrorCode.DISPLAY_NAME_REQUIRED, "displayName");
	}

	@Test
	void ログインとCSRFトークンが要る() {
		assertThat(this.mvc.get().uri("/api/users/taro_y")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(this.mvc.patch()
			.uri("/api/me/profile")
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(this.mvc.patch()
			.uri("/api/me/profile")
			.with(loggedIn(1))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).hasStatus(HttpStatus.FORBIDDEN);
		verifyNoInteractions(this.profileService);
	}

}

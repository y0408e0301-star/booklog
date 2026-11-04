package com.example.booklog.review;

import static com.example.booklog.ControllerTestSupport.assertError;
import static com.example.booklog.ControllerTestSupport.loggedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.example.booklog.ControllerTest;
import com.example.booklog.book.BookSummary;
import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.user.Author;

// レビューの API（FN-03 6 章）と本のレビューの一覧（FN-10 6 章）の入口：URL・ステータス・JSON の形・multipart の受け取り方。
// 処理の中身は ReviewServiceTests
@ControllerTest(ReviewController.class)
class ReviewControllerTests {

	private static final ReviewView VIEW = new ReviewView(1234, new Author("taro_y", "たろう"),
			new BookSummary(56, "こころ", List.of("夏目漱石"), null), 4, "面白かった", false, List.of(), 0, 0, false, true,
			Instant.parse("2026-11-01T06:30:00Z"), null);

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	ReviewService reviewService;

	@Test
	void 投稿はmultipartで受け取り201でReviewViewを返す() {
		when(this.reviewService.create(1, 56, new ReviewInput(4, "面白かった", true), List.of())).thenReturn(VIEW);

		assertThat(this.mvc.post()
			.uri("/api/books/56/reviews")
			.multipart()
			.param("rating", "4")
			.param("body", " 面白かった ")
			.param("spoiler", "true")
			.with(loggedIn(1))
			.with(csrf())).hasStatus(HttpStatus.CREATED).bodyJson().isStrictlyEqualTo("""
					{"id":1234,"author":{"username":"taro_y","displayName":"たろう"},
					 "book":{"id":56,"title":"こころ","authors":["夏目漱石"],"thumbnailUrl":null},
					 "rating":4,"body":"面白かった","spoiler":false,"photos":[],"helpfulCount":0,"commentCount":0,
					 "helpfulByMe":false,"mine":true,"createdAt":"2026-11-01T06:30:00Z","editedAt":null}
					""");
	}

	// TC-03-1：画面を通さずに 3.5 を送ったとき
	@Test
	void 星が整数でなければBAD_REQUEST() {
		assertError(this.mvc.post()
			.uri("/api/books/56/reviews")
			.multipart()
			.param("rating", "3.5")
			.param("body", "感想")
			.with(loggedIn(1))
			.with(csrf())
			.exchange(), ErrorCode.BAD_REQUEST);
		verifyNoInteractions(this.reviewService);
	}

	@Test
	void 星がなければM16() {
		assertError(this.mvc.post()
			.uri("/api/books/56/reviews")
			.multipart()
			.param("body", "感想")
			.with(loggedIn(1))
			.with(csrf())
			.exchange(), ErrorCode.RATING_REQUIRED, "rating");
	}

	@Test
	void 投稿にはCSRFトークンが要る() {
		assertThat(this.mvc.post()
			.uri("/api/books/56/reviews")
			.multipart()
			.param("rating", "4")
			.param("body", "感想")
			.with(loggedIn(1))).hasStatus(HttpStatus.FORBIDDEN);
		verifyNoInteractions(this.reviewService);
	}

	@Test
	void 同じ本のレビューがあれば409() {
		when(this.reviewService.create(anyLong(), anyLong(), any(), any()))
			.thenThrow(new ApiException(ErrorCode.REVIEW_EXISTS));
		assertError(this.mvc.post()
			.uri("/api/books/56/reviews")
			.multipart()
			.param("rating", "4")
			.param("body", "感想")
			.with(loggedIn(1))
			.with(csrf())
			.exchange(), ErrorCode.REVIEW_EXISTS);
	}

	// FN-04：写真はファイルの項目 photos、残す写真は keepPhotoIds で受け取る
	@Test
	void 写真と残す写真のIDを受け取る() {
		MockMultipartFile photo = new MockMultipartFile("photos", "a.png", "image/png", new byte[] { 1 });
		when(this.reviewService.edit(eq(1L), eq(1234L), eq(new ReviewInput(2, "読み直した", false)), eq(List.of(10L, 11L)),
				any()))
			.thenReturn(VIEW);
		assertThat(this.mvc.perform(multipart(HttpMethod.PATCH, "/api/reviews/1234").file(photo)
			.param("rating", "2")
			.param("body", "読み直した")
			.param("keepPhotoIds", "10", "11")
			.with(loggedIn(1))
			.with(csrf()))).hasStatusOk();
	}

	// FN-04 4 章：multipart の上限を超えたとき
	@Test
	void 写真が大きすぎてmultipartで断られたら413のPHOTO_TOO_LARGE() {
		when(this.reviewService.create(anyLong(), anyLong(), any(), any()))
			.thenThrow(new MaxUploadSizeExceededException(21));
		assertError(this.mvc.post()
			.uri("/api/books/56/reviews")
			.multipart()
			.param("rating", "4")
			.param("body", "感想")
			.with(loggedIn(1))
			.with(csrf())
			.exchange(), ErrorCode.PHOTO_TOO_LARGE, "photos");
	}

	@Test
	void 編集もmultipartで受け取る() {
		when(this.reviewService.edit(1, 1234, new ReviewInput(2, "読み直した", false), null, List.of())).thenReturn(VIEW);
		assertThat(this.mvc.perform(multipart(HttpMethod.PATCH, "/api/reviews/1234").param("rating", "2")
			.param("body", "読み直した")
			.with(loggedIn(1))
			.with(csrf()))).hasStatusOk();
	}

	// TC-03-6
	@Test
	void 他人のレビューの削除は403() {
		doThrow(new ApiException(ErrorCode.FORBIDDEN)).when(this.reviewService).delete(1, 1234);
		assertError(this.mvc.delete().uri("/api/reviews/1234").with(loggedIn(1)).with(csrf()).exchange(),
				ErrorCode.FORBIDDEN);
	}

	@Test
	void 削除すると204() {
		assertThat(this.mvc.delete().uri("/api/reviews/1234").with(loggedIn(1)).with(csrf()))
			.hasStatus(HttpStatus.NO_CONTENT);
		verify(this.reviewService).delete(1, 1234);
	}

	// TC-03-12
	@Test
	void 削除されたレビューは404() {
		when(this.reviewService.get(1, 1234)).thenThrow(new ApiException(ErrorCode.REVIEW_NOT_FOUND));
		assertError(this.mvc.get().uri("/api/reviews/1234").with(loggedIn(1)).exchange(), ErrorCode.REVIEW_NOT_FOUND);
	}

	@Test
	void 本のレビューの一覧はcursorを渡す() {
		when(this.reviewService.listByBook(1, 56, "80")).thenReturn(new ReviewService.ReviewPage(List.of(VIEW), null));
		assertThat(this.mvc.get().uri("/api/books/56/reviews?cursor=80").with(loggedIn(1))).hasStatusOk()
			.bodyJson()
			.extractingPath("$.nextCursor")
			.isNull();
	}

	@Test
	void タイムラインはタブとcursorを渡す() {
		when(this.reviewService.timeline(1, "following", "80"))
			.thenReturn(new ReviewService.ReviewPage(List.of(VIEW), "79"));
		assertThat(this.mvc.get().uri("/api/timeline?tab=following&cursor=80").with(loggedIn(1))).hasStatusOk()
			.bodyJson()
			.extractingPath("$.nextCursor")
			.isEqualTo("79");
	}

	@Test
	void ログインしていなければ使えない() {
		assertThat(this.mvc.get().uri("/api/reviews/1")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(this.mvc.get().uri("/api/books/56/reviews")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(this.mvc.get().uri("/api/timeline?tab=all")).hasStatus(HttpStatus.UNAUTHORIZED);
		verifyNoInteractions(this.reviewService);
	}

}

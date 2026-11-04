package com.example.booklog.shelf;

import static com.example.booklog.ControllerTestSupport.assertError;
import static com.example.booklog.ControllerTestSupport.loggedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.example.booklog.ControllerTest;
import com.example.booklog.book.BookSummary;
import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;

// 読書ステータスと本棚の API の入口（FN-08 6 章）：URL・ステータス・JSON の形。処理の中身は ShelfServiceTests
@ControllerTest(ShelfController.class)
class ShelfControllerTests {

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	ShelfService shelfService;

	@Test
	void 付けると今のステータスを返す() {
		when(this.shelfService.set(1, 56, "READING")).thenReturn(ReadingStatus.READING);
		assertThat(this.mvc.put()
			.uri("/api/books/56/status")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"READING\"}")
			.with(loggedIn(1))
			.with(csrf())).hasStatusOk().bodyJson().isStrictlyEqualTo("{\"status\":\"READING\"}");
	}

	@Test
	void 外すとnullを返す() {
		assertThat(this.mvc.delete().uri("/api/books/56/status").with(loggedIn(1)).with(csrf())).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("{\"status\":null}");
		verify(this.shelfService).remove(1, 56);
	}

	@Test
	void 変えるにはログインとCSRFトークンが要る() {
		assertThat(this.mvc.put()
			.uri("/api/books/56/status")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"READ\"}")
			.with(csrf())).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(this.mvc.delete().uri("/api/books/56/status").with(loggedIn(1))).hasStatus(HttpStatus.FORBIDDEN);
		verifyNoInteractions(this.shelfService);
	}

	@Test
	void ない本は404() {
		when(this.shelfService.set(1, 999, "READ")).thenThrow(new ApiException(ErrorCode.BOOK_NOT_FOUND));
		assertError(this.mvc.put()
			.uri("/api/books/999/status")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"READ\"}")
			.with(loggedIn(1))
			.with(csrf())
			.exchange(), ErrorCode.BOOK_NOT_FOUND);
	}

	@Test
	void 本棚の一覧を返す() {
		ReadingStatuses.ShelfItem item = new ReadingStatuses.ShelfItem(
				new BookSummary(56, "こころ", List.of("夏目漱石"), null), ReadingStatus.READ, 4,
				Instant.parse("2026-11-04T03:00:00Z"));
		when(this.shelfService.list("taro_y", "READ", "1_2"))
			.thenReturn(new ShelfService.ShelfPage(List.of(item), "1793761200000000_56"));

		assertThat(this.mvc.get().uri("/api/users/taro_y/bookshelf?status=READ&cursor=1_2").with(loggedIn(2)))
			.hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("""
					{"items":[{"book":{"id":56,"title":"こころ","authors":["夏目漱石"],"thumbnailUrl":null},
					  "status":"READ","rating":4,"updatedAt":"2026-11-04T03:00:00Z"}],
					 "nextCursor":"1793761200000000_56"}
					""");
	}

	@Test
	void いないユーザーの本棚は404() {
		when(this.shelfService.list("nobody", "READ", null)).thenThrow(new ApiException(ErrorCode.USER_NOT_FOUND));
		assertError(this.mvc.get().uri("/api/users/nobody/bookshelf?status=READ").with(loggedIn(1)).exchange(),
				ErrorCode.USER_NOT_FOUND);
	}

}

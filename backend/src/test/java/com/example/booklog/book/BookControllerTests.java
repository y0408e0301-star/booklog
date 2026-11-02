package com.example.booklog.book;

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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.example.booklog.ControllerTest;
import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;

// 書籍検索・本の取り込み（FN-02 6 章）と本の情報（FN-10 6 章）の API の入口：URL・ステータス・JSON の形。処理の中身は BookServiceTests
@ControllerTest(BookController.class)
class BookControllerTests {

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	BookService bookService;

	@Test
	void 検索の結果を返す() {
		when(this.bookService.search("夏目漱石", 2)).thenReturn(new BookSearchPage(
				List.of(new BookSearchItem("a", "こころ", List.of("夏目漱石"), null, "2003", null, null)), true));

		assertThat(this.mvc.get().uri("/api/books/search?q={q}&page=2", "夏目漱石").with(loggedIn(1))).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("""
					{"items":[{"googleBooksId":"a","title":"こころ","authors":["夏目漱石"],"publisher":null,
					  "publishedDate":"2003","isbn13":null,"thumbnailUrl":null}],"hasNext":true}
					""");
	}

	// TC-02-2
	@Test
	void 検索の文字が空ならQUERY_REQUIRED() {
		when(this.bookService.search(null, null)).thenThrow(new ApiException(ErrorCode.QUERY_REQUIRED, "q"));
		assertError(this.mvc.get().uri("/api/books/search").with(loggedIn(1)).exchange(), ErrorCode.QUERY_REQUIRED,
				"q");
	}

	@Test
	void ページが数でなければBAD_REQUEST() {
		assertError(this.mvc.get().uri("/api/books/search?q=a&page=x").with(loggedIn(1)).exchange(),
				ErrorCode.BAD_REQUEST);
	}

	// TC-02-6
	@Test
	void GoogleBooksAPIが使えなければ503() {
		when(this.bookService.search("猫", null)).thenThrow(new ApiException(ErrorCode.BOOK_SEARCH_UNAVAILABLE));
		assertError(this.mvc.get().uri("/api/books/search?q=猫").with(loggedIn(1)).exchange(),
				ErrorCode.BOOK_SEARCH_UNAVAILABLE);
	}

	@Test
	void 新しく取り込んだら201でもうあれば200() {
		when(this.bookService.importBook("a")).thenReturn(new BookService.ImportResult(56, true));
		when(this.bookService.importBook("b")).thenReturn(new BookService.ImportResult(57, false));

		assertThat(importBook("a")).hasStatus(HttpStatus.CREATED).bodyJson().isStrictlyEqualTo("{\"id\":56}");
		assertThat(importBook("b")).hasStatus(HttpStatus.OK).bodyJson().isStrictlyEqualTo("{\"id\":57}");
	}

	@Test
	void 本の取り込みにはCSRFトークンが要る() {
		assertThat(this.mvc.post()
			.uri("/api/books")
			.with(loggedIn(1))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"googleBooksId\":\"a\"}")).hasStatus(HttpStatus.FORBIDDEN);
		verifyNoInteractions(this.bookService);
	}

	@Test
	void 本の情報を返す() {
		when(this.bookService.detail(56)).thenReturn(new BookService.BookDetail(56, "吾輩は猫である", List.of("夏目漱石"), "新潮社",
				"2003-06", "9784101010014", "紹介文", "https://books.google.com/a"));

		assertThat(this.mvc.get().uri("/api/books/56").with(loggedIn(1))).hasStatusOk().bodyJson().isStrictlyEqualTo("""
				{"id":56,"title":"吾輩は猫である","authors":["夏目漱石"],"publisher":"新潮社","publishedDate":"2003-06",
				 "isbn13":"9784101010014","description":"紹介文","thumbnailUrl":"https://books.google.com/a"}
				""");
	}

	// TC-10-6
	@Test
	void ない本は404() {
		when(this.bookService.detail(999)).thenThrow(new ApiException(ErrorCode.BOOK_NOT_FOUND));
		assertError(this.mvc.get().uri("/api/books/999").with(loggedIn(1)).exchange(), ErrorCode.BOOK_NOT_FOUND);
	}

	@Test
	void ログインしていなければ使えない() {
		assertThat(this.mvc.get().uri("/api/books/search?q=a")).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(this.mvc.get().uri("/api/books/1")).hasStatus(HttpStatus.UNAUTHORIZED);
		verifyNoInteractions(this.bookService);
	}

	private MvcTestResult importBook(String googleBooksId) {
		return this.mvc.post()
			.uri("/api/books")
			.with(loggedIn(1))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"googleBooksId\":\"" + googleBooksId + "\"}")
			.exchange();
	}

}

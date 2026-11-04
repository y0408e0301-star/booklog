package com.example.booklog.book;

import static com.example.booklog.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.booklog.TestEntities;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.shelf.ReadingStatus;
import com.example.booklog.shelf.ReadingStatuses;

// 書籍検索・本の取り込み（FN-02）と本の情報（FN-10）の処理を、データベースと Google Books API を使わずに確かめる
@ExtendWith(MockitoExtension.class)
class BookServiceTests {

	private static final BookData DATA = new BookData("zyTCAlFPjgYC", "9784101010014", "吾輩は猫である", List.of("夏目漱石"),
			"新潮社", "2003-06", "紹介文", "https://books.google.com/a");

	@Mock
	BookCatalog catalog;

	@Mock
	BookRepository books;

	@Mock
	BookReviewStats reviewStats;

	@Mock
	ReadingStatuses readingStatuses;

	private BookService service;

	@BeforeEach
	void setUp() {
		this.service = new BookService(this.catalog, this.books, this.reviewStats, this.readingStatuses,
				TestEntities.fixedClock());
	}

	@Test
	void ページを省いたら1ページ目を探す() {
		BookSearchPage page = new BookSearchPage(List.of(), false);
		when(this.catalog.search(BookQuery.parse("猫"), 1)).thenReturn(page);
		assertThat(this.service.search(" 猫 ", null)).isSameAs(page);
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, 6, -1 })
	void ページは1から5まで(int page) {
		assertApiError(() -> this.service.search("猫", page), ErrorCode.BAD_REQUEST);
		verifyNoInteractions(this.catalog);
	}

	// TC-02-2
	@Test
	void 空白だけなら探さない() {
		assertApiError(() -> this.service.search("  ", 1), ErrorCode.QUERY_REQUIRED, "q");
		verifyNoInteractions(this.catalog);
	}

	// B-6
	@Test
	void もう取り込んだ本はGoogleBooksAPIを呼ばずにその本を返す() {
		when(this.books.findByGoogleBooksId("zyTCAlFPjgYC"))
			.thenReturn(Optional.of(TestEntities.withId(new Book(DATA, TestEntities.NOW), 56)));

		assertThat(this.service.importBook("zyTCAlFPjgYC")).isEqualTo(new BookService.ImportResult(56, false));
		verifyNoInteractions(this.catalog);
	}

	@Test
	void まだなければGoogleBooksAPIから取って選んだIDで保存する() {
		when(this.books.findByGoogleBooksId("zyTCAlFPjgYC")).thenReturn(Optional.empty());
		// Google Books API が別の ID を返しても、選んだ ID で持つ
		when(this.catalog.find("zyTCAlFPjgYC"))
			.thenReturn(Optional.of(new BookData("other", DATA.isbn13(), DATA.title(), DATA.authors(), DATA.publisher(),
					DATA.publishedDate(), DATA.description(), DATA.thumbnailUrl())));
		when(this.books.saveAndFlush(any())).thenAnswer((call) -> TestEntities.withId(call.getArgument(0), 57));

		assertThat(this.service.importBook("zyTCAlFPjgYC")).isEqualTo(new BookService.ImportResult(57, true));
		ArgumentCaptor<Book> saved = ArgumentCaptor.forClass(Book.class);
		verify(this.books).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getGoogleBooksId()).isEqualTo("zyTCAlFPjgYC");
		assertThat(saved.getValue().getTitle()).isEqualTo("吾輩は猫である");
	}

	// TC-02-7：同時に取り込まれて一意の制約で失敗したら、探し直して返す（BR-11）
	@Test
	void 同時に取り込まれたら探し直して返す() {
		when(this.books.findByGoogleBooksId("zyTCAlFPjgYC")).thenReturn(Optional.empty(),
				Optional.of(TestEntities.withId(new Book(DATA, TestEntities.NOW), 56)));
		when(this.catalog.find("zyTCAlFPjgYC")).thenReturn(Optional.of(DATA));
		when(this.books.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

		assertThat(this.service.importBook("zyTCAlFPjgYC")).isEqualTo(new BookService.ImportResult(56, false));
	}

	@Test
	void GoogleBooksにない本はM15() {
		when(this.books.findByGoogleBooksId("gone")).thenReturn(Optional.empty());
		when(this.catalog.find("gone")).thenReturn(Optional.empty());
		assertApiError(() -> this.service.importBook("gone"), ErrorCode.GOOGLE_BOOK_NOT_FOUND);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "a/b", "a b", "あ", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" })
	void GoogleBooksのIDの形が違えば探さない(String id) {
		assertApiError(() -> this.service.importBook(id), ErrorCode.BAD_REQUEST);
		verifyNoInteractions(this.books, this.catalog);
	}

	// BK-1・BK-3・BK-4
	@Test
	void 本の情報と平均評価と件数と自分のレビューとステータス() {
		when(this.books.findById(56L))
			.thenReturn(Optional.of(TestEntities.withId(new Book(DATA, TestEntities.NOW), 56)));
		when(this.reviewStats.of(56)).thenReturn(new BookReviewStats.Stats(4.3, 3));
		when(this.reviewStats.myReviewId(1, 56)).thenReturn(1234L);
		when(this.readingStatuses.statusOf(1, 56)).thenReturn(ReadingStatus.READ);
		assertThat(this.service.detail(1, 56))
			.isEqualTo(new BookService.BookDetail(56, "吾輩は猫である", List.of("夏目漱石"), "新潮社", "2003-06", "9784101010014",
					"紹介文", "https://books.google.com/a", 4.3, 3, 1234L, ReadingStatus.READ));
	}

	// TC-10-6
	@Test
	void ない本はM15() {
		when(this.books.findById(999L)).thenReturn(Optional.empty());
		assertApiError(() -> this.service.detail(1, 999), ErrorCode.BOOK_NOT_FOUND);
	}

}

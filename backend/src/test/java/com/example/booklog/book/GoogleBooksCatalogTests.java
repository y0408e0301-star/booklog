package com.example.booklog.book;

import static com.example.booklog.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.booklog.TestEntities;
import com.example.booklog.common.ErrorCode;

// 検索の結果の使い回し（B-9、BR-12）と呼び出し回数（BR-13）を、データベースと Google Books API を使わずに確かめる
@ExtendWith(MockitoExtension.class)
class GoogleBooksCatalogTests {

	// TestEntities.NOW（2026-10-24T03:00:00Z）は、日本時間の 2026-10-24 12:00
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

	private static final BookQuery QUERY = BookQuery.parse("夏目漱石");

	@Mock
	GoogleBooksClient client;

	@Mock
	BookSearchCache cache;

	@Mock
	GoogleBooksUsage usage;

	private GoogleBooksCatalog catalog;

	@BeforeEach
	void setUp() {
		this.catalog = new GoogleBooksCatalog(this.client, this.cache, this.usage, TestEntities.fixedClock());
	}

	// TC-02-4
	@Test
	void 二十四時間以内の結果があればGoogleBooksAPIを呼ばず回数も数えない() {
		BookSearchPage cached = new BookSearchPage(List.of(), false);
		when(this.cache.find("q:夏目漱石", 1, TestEntities.NOW.minusSeconds(24 * 60 * 60))).thenReturn(Optional.of(cached));

		assertThat(this.catalog.search(QUERY, 1)).isSameAs(cached);
		verifyNoInteractions(this.client, this.usage);
	}

	@Test
	void なければ今日の回数を数えてから呼び結果を保存する() {
		when(this.cache.find(anyString(), anyInt(), any())).thenReturn(Optional.empty());
		when(this.usage.increment(TODAY)).thenReturn(OptionalInt.of(1));
		when(this.client.search(QUERY, 2)).thenReturn(List.of(book("a")));

		BookSearchPage result = this.catalog.search(QUERY, 2);

		assertThat(result.items()).extracting(BookSearchItem::googleBooksId).containsExactly("a");
		assertThat(result.hasNext()).isFalse();
		verify(this.cache).save("q:夏目漱石", 2, result, TestEntities.NOW);
	}

	// TC-02-3：20 件そろったら次のページがある。5 ページ目（100 件）より後は出さない
	@Test
	void 二十件そろったら次がある() {
		when(this.cache.find(anyString(), anyInt(), any())).thenReturn(Optional.empty());
		when(this.usage.increment(TODAY)).thenReturn(OptionalInt.of(1));
		List<BookData> twenty = Collections.nCopies(20, book("a"));
		when(this.client.search(any(), anyInt())).thenReturn(twenty);

		assertThat(this.catalog.search(QUERY, 4).hasNext()).isTrue();
		assertThat(this.catalog.search(QUERY, 5).hasNext()).isFalse();
	}

	// TC-02-5
	@Test
	void 今日の上限に達していたら呼ばずに使えないとする() {
		when(this.cache.find(anyString(), anyInt(), any())).thenReturn(Optional.empty());
		when(this.usage.increment(TODAY)).thenReturn(OptionalInt.empty());

		assertApiError(() -> this.catalog.search(QUERY, 1), ErrorCode.BOOK_SEARCH_UNAVAILABLE);
		assertApiError(() -> this.catalog.find("a"), ErrorCode.BOOK_SEARCH_UNAVAILABLE);
		verifyNoInteractions(this.client);
	}

	// TC-02-6：エラーのときは保存しない（BR-12）
	@Test
	void GoogleBooksAPIが使えなければ保存せずに使えないとする() {
		when(this.cache.find(anyString(), anyInt(), any())).thenReturn(Optional.empty());
		when(this.usage.increment(TODAY)).thenReturn(OptionalInt.of(5));
		when(this.client.search(QUERY, 1)).thenThrow(new GoogleBooksUnavailableException(503, "Service Unavailable"));

		assertApiError(() -> this.catalog.search(QUERY, 1), ErrorCode.BOOK_SEARCH_UNAVAILABLE);
		verify(this.cache, never()).save(anyString(), anyInt(), any(), any());
	}

	@Test
	void 一冊の情報も回数を数えてから呼ぶ() {
		when(this.usage.increment(TODAY)).thenReturn(OptionalInt.of(801));
		when(this.client.volume("a")).thenReturn(Optional.of(book("a")));

		assertThat(this.catalog.find("a")).contains(book("a"));
		verifyNoInteractions(this.cache);
	}

	private static BookData book(String id) {
		return new BookData(id, null, "書名", List.of(), null, null, "紹介文", null);
	}

}

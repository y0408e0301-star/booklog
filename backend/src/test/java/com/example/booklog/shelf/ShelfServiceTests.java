package com.example.booklog.shelf;

import static com.example.booklog.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

import com.example.booklog.TestEntities;
import com.example.booklog.book.BookRepository;
import com.example.booklog.book.BookSummary;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.user.UserRepository;

// 読書ステータスの付け外しと本棚の一覧の処理の順番とエラー（FN-08）
@ExtendWith(MockitoExtension.class)
class ShelfServiceTests {

	@Mock
	ReadingStatuses statuses;

	@Mock
	BookRepository books;

	@Mock
	UserRepository users;

	private ShelfService service;

	@BeforeEach
	void setUp() {
		this.service = new ShelfService(this.statuses, this.books, this.users);
	}

	@Test
	void 付ける() {
		when(this.books.existsById(56L)).thenReturn(true);
		assertThat(this.service.set(1, 56, "READING")).isEqualTo(ReadingStatus.READING);
		verify(this.statuses).set(1, 56, ReadingStatus.READING);
	}

	@Test
	void ステータスが違えば本を探さず400() {
		assertApiError(() -> this.service.set(1, 56, "DONE"), ErrorCode.BAD_REQUEST, "status");
		verifyNoInteractions(this.books, this.statuses);
	}

	@Test
	void ない本は付けても外しても404() {
		when(this.books.existsById(999L)).thenReturn(false);
		assertApiError(() -> this.service.set(1, 999, "READ"), ErrorCode.BOOK_NOT_FOUND);
		assertApiError(() -> this.service.remove(1, 999), ErrorCode.BOOK_NOT_FOUND);
		verifyNoInteractions(this.statuses);
	}

	@Test
	void 外す() {
		when(this.books.existsById(56L)).thenReturn(true);
		this.service.remove(1, 56);
		verify(this.statuses).remove(1, 56);
	}

	// TC-08-5：21 冊あれば 20 冊と続きの位置を返す
	@Test
	void 二十冊ずつ読み続きの位置を返す() {
		when(this.users.findByUsername("taro_y")).thenReturn(Optional.of(TestEntities.user(1, "taro_y")));
		List<ReadingStatuses.ShelfItem> found = LongStream.rangeClosed(1, 21)
			.mapToObj(ShelfServiceTests::item)
			.toList();
		when(this.statuses.page(1, ReadingStatus.READ, null, 21)).thenReturn(found);

		ShelfService.ShelfPage page = this.service.list("taro_y", "READ", null);
		assertThat(page.items()).hasSize(20).isEqualTo(found.subList(0, 20));
		assertThat(page.nextCursor()).isEqualTo(ShelfCursor.after(found.get(19)).format());
	}

	@Test
	void 最後まで読めば続きの位置はnull() {
		when(this.users.findByUsername("taro_y")).thenReturn(Optional.of(TestEntities.user(1, "taro_y")));
		ShelfCursor after = new ShelfCursor(Instant.parse("2026-10-24T03:00:00Z"), 5);
		when(this.statuses.page(1, ReadingStatus.WANT, after, 21)).thenReturn(List.of(item(4)));

		ShelfService.ShelfPage page = this.service.list("taro_y", "WANT", after.format());
		assertThat(page.items()).hasSize(1);
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void いないユーザーは404() {
		when(this.users.findByUsername("nobody")).thenReturn(Optional.empty());
		assertApiError(() -> this.service.list("nobody", "READ", null), ErrorCode.USER_NOT_FOUND);
		verifyNoInteractions(this.statuses);
	}

	@Test
	void タブや続きの位置が違えば400() {
		assertApiError(() -> this.service.list("taro_y", null, null), ErrorCode.BAD_REQUEST, "status");
		assertApiError(() -> this.service.list("taro_y", "READ", "x"), ErrorCode.BAD_REQUEST, "cursor");
		verifyNoInteractions(this.users, this.statuses);
	}

	private static ReadingStatuses.ShelfItem item(long bookId) {
		return new ReadingStatuses.ShelfItem(new BookSummary(bookId, "本 " + bookId, List.of(), null),
				ReadingStatus.READ, null, Instant.parse("2026-10-24T03:00:00Z").minusSeconds(bookId));
	}

}

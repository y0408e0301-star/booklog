package com.example.booklog.review;

import static com.example.booklog.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.example.booklog.book.Book;
import com.example.booklog.book.BookData;
import com.example.booklog.book.BookRepository;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.shelf.ReadingStatuses;
import com.example.booklog.user.User;
import com.example.booklog.user.UserRepository;

// レビューの投稿・表示・編集・削除（FN-03）と本のレビューの一覧（BK-5）の処理を、データベースを使わずに確かめる
@ExtendWith(MockitoExtension.class)
class ReviewServiceTests {

	private static final ReviewInput INPUT = new ReviewInput(4, "面白かった", false);

	@Mock
	ReviewRepository reviews;

	@Mock
	BookRepository books;

	@Mock
	UserRepository users;

	@Mock
	ReadingStatuses readingStatuses;

	private ReviewService service;

	private final User taro = TestEntities.user(1, "taro_y");

	private final User hanako = TestEntities.user(2, "hanako");

	private final Book book = TestEntities.withId(
			new Book(new BookData("g", null, "こころ", List.of("夏目漱石"), null, null, null, null), TestEntities.NOW), 56);

	@BeforeEach
	void setUp() {
		this.service = new ReviewService(this.reviews, this.books, this.users, this.readingStatuses,
				TestEntities.fixedClock());
	}

	@Test
	void 投稿すると本棚になければ読了で入れる() {
		when(this.users.findById(1L)).thenReturn(Optional.of(this.taro));
		when(this.books.findById(56L)).thenReturn(Optional.of(this.book));
		when(this.reviews.saveAndFlush(any())).thenAnswer((call) -> TestEntities.withId(call.getArgument(0), 1234));

		ReviewView view = this.service.create(1, 56, INPUT);

		assertThat(view.id()).isEqualTo(1234);
		assertThat(view.rating()).isEqualTo(4);
		assertThat(view.mine()).isTrue();
		assertThat(view.createdAt()).isEqualTo(TestEntities.NOW);
		assertThat(view.editedAt()).isNull();
		assertThat(view.book()).isEqualTo(new ReviewView.BookSummary(56, "こころ", List.of("夏目漱石"), null));
		verify(this.readingStatuses).addReadIfAbsent(1, 56);
	}

	// TC-03-3
	@Test
	void 同じ本のレビューがあれば409で本棚も変えない() {
		when(this.users.findById(1L)).thenReturn(Optional.of(this.taro));
		when(this.books.findById(56L)).thenReturn(Optional.of(this.book));
		when(this.reviews.existsByAuthorAndBook(1, 56)).thenReturn(true);

		assertApiError(() -> this.service.create(1, 56, INPUT), ErrorCode.REVIEW_EXISTS);
		verify(this.reviews, never()).saveAndFlush(any());
		verify(this.readingStatuses, never()).addReadIfAbsent(anyLong(), anyLong());
	}

	// TC-03-3：同時に投稿されて一意の制約で失敗したとき
	@Test
	void 同時に投稿されたら409() {
		when(this.users.findById(1L)).thenReturn(Optional.of(this.taro));
		when(this.books.findById(56L)).thenReturn(Optional.of(this.book));
		when(this.reviews.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

		assertApiError(() -> this.service.create(1, 56, INPUT), ErrorCode.REVIEW_EXISTS);
		verify(this.readingStatuses, never()).addReadIfAbsent(anyLong(), anyLong());
	}

	@Test
	void ない本には書けない() {
		when(this.users.findById(1L)).thenReturn(Optional.of(this.taro));
		when(this.books.findById(999L)).thenReturn(Optional.empty());
		assertApiError(() -> this.service.create(1, 999, INPUT), ErrorCode.BOOK_NOT_FOUND);
	}

	@Test
	void 退会した人のJWTでは書けない() {
		when(this.users.findById(9L)).thenReturn(Optional.empty());
		assertApiError(() -> this.service.create(9, 56, INPUT), ErrorCode.UNAUTHENTICATED);
	}

	@Test
	void 編集すると編集日時を記録し投稿日時は変えない() {
		Review review = review(1234, this.taro);
		when(this.reviews.findWithAuthorAndBookById(1234)).thenReturn(Optional.of(review));

		ReviewView view = this.service.edit(1, 1234, new ReviewInput(2, "読み直した", true));

		assertThat(view.rating()).isEqualTo(2);
		assertThat(view.body()).isEqualTo("読み直した");
		assertThat(view.spoiler()).isTrue();
		assertThat(view.editedAt()).isEqualTo(TestEntities.NOW);
		assertThat(view.createdAt()).isEqualTo(TestEntities.NOW.minusSeconds(60));
	}

	// TC-03-6
	@Test
	void 他人のレビューは編集も削除もできない() {
		when(this.reviews.findWithAuthorAndBookById(1234)).thenReturn(Optional.of(review(1234, this.hanako)));

		assertApiError(() -> this.service.edit(1, 1234, INPUT), ErrorCode.FORBIDDEN);
		assertApiError(() -> this.service.delete(1, 1234), ErrorCode.FORBIDDEN);
		verify(this.reviews, never()).delete(any());
	}

	@Test
	void 自分のレビューを削除する() {
		Review review = review(1234, this.taro);
		when(this.reviews.findWithAuthorAndBookById(1234)).thenReturn(Optional.of(review));
		this.service.delete(1, 1234);
		verify(this.reviews).delete(review);
	}

	// TC-03-12
	@Test
	void 削除されたレビューはM20() {
		when(this.reviews.findWithAuthorAndBookById(1234)).thenReturn(Optional.empty());
		assertApiError(() -> this.service.get(1, 1234), ErrorCode.REVIEW_NOT_FOUND);
		assertApiError(() -> this.service.edit(1, 1234, INPUT), ErrorCode.REVIEW_NOT_FOUND);
		assertApiError(() -> this.service.delete(1, 1234), ErrorCode.REVIEW_NOT_FOUND);
	}

	@Test
	void ほかの人のレビューはmineがfalse() {
		when(this.reviews.findWithAuthorAndBookById(1234)).thenReturn(Optional.of(review(1234, this.hanako)));
		assertThat(this.service.get(1, 1234).mine()).isFalse();
	}

	// BK-5：21 件取れたら 20 件を返し、最後の ID を次のカーソルにする
	@Test
	void 一覧は20件ずつで続きがあれば最後のIDを返す() {
		when(this.books.existsById(56L)).thenReturn(true);
		List<Review> found = LongStream.rangeClosed(1, 21)
			.map((i) -> 100 - i)
			.mapToObj((id) -> review(id, this.taro))
			.toList();
		when(this.reviews.findByBookBefore(56, Long.MAX_VALUE, Limit.of(21))).thenReturn(found);

		ReviewService.ReviewPage page = this.service.listByBook(1, 56, null);

		assertThat(page.items()).hasSize(20);
		assertThat(page.items().getFirst().id()).isEqualTo(99);
		assertThat(page.nextCursor()).isEqualTo("80");
	}

	@Test
	void 最後まで読んだら次のカーソルはない() {
		when(this.books.existsById(56L)).thenReturn(true);
		when(this.reviews.findByBookBefore(eq(56L), eq(80L), any())).thenReturn(List.of(review(79, this.taro)));
		assertThat(this.service.listByBook(1, 56, "80").nextCursor()).isNull();
	}

	@Test
	void カーソルが数でなければBAD_REQUEST() {
		when(this.books.existsById(56L)).thenReturn(true);
		assertApiError(() -> this.service.listByBook(1, 56, "abc"), ErrorCode.BAD_REQUEST);
	}

	@Test
	void ない本の一覧はM15() {
		when(this.books.existsById(999L)).thenReturn(false);
		assertApiError(() -> this.service.listByBook(1, 999, null), ErrorCode.BOOK_NOT_FOUND);
	}

	private Review review(long id, User author) {
		return TestEntities.withId(new Review(author, this.book, INPUT, TestEntities.NOW.minusSeconds(60)), id);
	}

}

package com.example.booklog.review;

import java.time.Clock;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.booklog.book.Book;
import com.example.booklog.book.BookRepository;
import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.shelf.ReadingStatuses;
import com.example.booklog.user.User;
import com.example.booklog.user.UserRepository;

// レビューの投稿・表示・編集・削除（機能定義書 FN-03）と、本のページのレビューの一覧（FN-10 BK-5）
@Service
class ReviewService {

	// 一覧の 1 回の件数（機能定義書 3.4）
	static final int PAGE_SIZE = 20;

	private final ReviewRepository reviews;

	private final BookRepository books;

	private final UserRepository users;

	private final ReadingStatuses readingStatuses;

	private final Clock clock;

	ReviewService(ReviewRepository reviews, BookRepository books, UserRepository users, ReadingStatuses readingStatuses,
			Clock clock) {
		this.reviews = reviews;
		this.books = books;
		this.users = users;
		this.readingStatuses = readingStatuses;
		this.clock = clock;
	}

	// 投稿と本棚への「読了」の追加を 1 つのトランザクションで行う（RV-5、FN-03 5 章の 2）
	@Transactional
	ReviewView create(long userId, long bookId, ReviewInput input) {
		User author = this.users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
		Book book = this.books.findById(bookId).orElseThrow(() -> new ApiException(ErrorCode.BOOK_NOT_FOUND));
		if (this.reviews.existsByAuthorAndBook(userId, bookId)) {
			throw new ApiException(ErrorCode.REVIEW_EXISTS);
		}
		Review review;
		try {
			// 同時に同じ本のレビューが投稿されたときは、(user_id, book_id) の一意の制約で失敗する（BR-2）
			review = this.reviews.saveAndFlush(new Review(author, book, input, this.clock.instant()));
		}
		catch (DataIntegrityViolationException ex) {
			throw new ApiException(ErrorCode.REVIEW_EXISTS);
		}
		this.readingStatuses.addReadIfAbsent(userId, bookId);
		return ReviewView.of(review, userId);
	}

	@Transactional(readOnly = true)
	ReviewView get(long viewerId, long id) {
		return ReviewView.of(find(id), viewerId);
	}

	// 自分のレビューだけ。投稿日時・参考になった・コメントは変えない（RV-10）
	@Transactional
	ReviewView edit(long userId, long id, ReviewInput input) {
		Review review = findOwn(userId, id);
		review.edit(input, this.clock.instant());
		return ReviewView.of(review, userId);
	}

	// 自分のレビューだけ。写真・コメント・参考になったも一緒に消え、本と本棚は残す（RV-9、FN-03 5 章の 4）
	@Transactional
	void delete(long userId, long id) {
		this.reviews.delete(findOwn(userId, id));
	}

	// 本のページのレビューの一覧：新しい順に 20 件ずつ（BK-5）。cursor は前回の nextCursor（最後に出したレビューの ID）
	@Transactional(readOnly = true)
	ReviewPage listByBook(long viewerId, long bookId, @Nullable String cursor) {
		if (!this.books.existsById(bookId)) {
			throw new ApiException(ErrorCode.BOOK_NOT_FOUND);
		}
		// 21 件目は「続きがあるか」を知るため（データ設計書 8.1）
		List<Review> found = this.reviews.findByBookBefore(bookId, parseCursor(cursor), Limit.of(PAGE_SIZE + 1));
		List<Review> page = found.subList(0, Math.min(found.size(), PAGE_SIZE));
		String next = (found.size() > PAGE_SIZE) ? String.valueOf(page.getLast().getId()) : null;
		return new ReviewPage(page.stream().map((review) -> ReviewView.of(review, viewerId)).toList(), next);
	}

	private Review find(long id) {
		return this.reviews.findWithAuthorAndBookById(id)
			.orElseThrow(() -> new ApiException(ErrorCode.REVIEW_NOT_FOUND));
	}

	// 書いた人でなければ 403（BR-7）
	private Review findOwn(long userId, long id) {
		Review review = find(id);
		if (!review.isWrittenBy(userId)) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		return review;
	}

	// 最初は cursor なし（bigint の最大値より古いもの）。画面はカーソルの中身を読まない（機能定義書 3.4）
	private static long parseCursor(@Nullable String cursor) {
		if (cursor == null) {
			return Long.MAX_VALUE;
		}
		try {
			return Long.parseLong(cursor);
		}
		catch (NumberFormatException ex) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
	}

	// 一覧の形（機能定義書 3.4）。nextCursor が null なら最後まで読んだ
	record ReviewPage(List<ReviewView> items, @Nullable String nextCursor) {

		ReviewPage {
			items = List.copyOf(items);
		}

	}

}

package com.example.booklog.shelf;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.booklog.book.BookRepository;
import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.user.User;
import com.example.booklog.user.UserRepository;

// 読書ステータスの付け外しと本棚の一覧（FN-08）。変えられるのはログインした人の本棚だけ（ST-7）
@Service
class ShelfService {

	static final int PAGE_SIZE = 20;

	private final ReadingStatuses statuses;

	private final BookRepository books;

	private final UserRepository users;

	ShelfService(ReadingStatuses statuses, BookRepository books, UserRepository users) {
		this.statuses = statuses;
		this.books = books;
		this.users = users;
	}

	// 付ける・変える（ST-2）。何回送っても同じ結果になる（FN-08 5 章の 2）
	@Transactional
	ReadingStatus set(long userId, long bookId, @Nullable String status) {
		ReadingStatus parsed = ReadingStatus.parse(status);
		requireBook(bookId);
		this.statuses.set(userId, bookId, parsed);
		return parsed;
	}

	// 外す（ST-3）。レビューは消さない
	@Transactional
	void remove(long userId, long bookId) {
		requireBook(bookId);
		this.statuses.remove(userId, bookId);
	}

	// 本棚の一覧（ST-5）。誰の本棚でも見られる（ST-7）
	@Transactional(readOnly = true)
	ShelfPage list(String username, @Nullable String status, @Nullable String cursor) {
		ReadingStatus parsed = ReadingStatus.parse(status);
		ShelfCursor after = ShelfCursor.parse(cursor);
		User user = this.users.findByUsername(username).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
		List<ReadingStatuses.ShelfItem> found = this.statuses.page(user.getId(), parsed, after, PAGE_SIZE + 1);
		List<ReadingStatuses.ShelfItem> page = found.subList(0, Math.min(found.size(), PAGE_SIZE));
		String next = (found.size() > PAGE_SIZE) ? ShelfCursor.after(page.getLast()).format() : null;
		return new ShelfPage(page, next);
	}

	private void requireBook(long bookId) {
		if (!this.books.existsById(bookId)) {
			throw new ApiException(ErrorCode.BOOK_NOT_FOUND);
		}
	}

	// 一覧の形（機能定義書 3.4）。nextCursor が null なら最後まで読んだ
	record ShelfPage(List<ReadingStatuses.ShelfItem> items, @Nullable String nextCursor) {

		ShelfPage {
			items = List.copyOf(items);
		}

	}

}

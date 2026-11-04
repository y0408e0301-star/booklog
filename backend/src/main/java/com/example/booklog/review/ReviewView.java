package com.example.booklog.review;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.example.booklog.book.Book;
import com.example.booklog.user.User;

// レビューを返すときの形（機能定義書 3.5 ReviewView）。spoiler が true でも body と photos は返す（伏せるのは画面。FN-03 5 章の 5）
public record ReviewView(long id, Author author, BookSummary book, int rating, String body, boolean spoiler,
		List<Photo> photos, long helpfulCount, long commentCount, boolean helpfulByMe, boolean mine, Instant createdAt,
		@Nullable Instant editedAt) {

	public ReviewView {
		photos = List.copyOf(photos);
	}

	// 写真は ReviewViews がまとめて取って渡す。参考になった（1-8）・コメント（1-9）は、その機能を作るときに足す
	static ReviewView of(Review review, long viewerId, List<Photo> photos) {
		return new ReviewView(review.getId(), Author.of(review.getAuthor()), BookSummary.of(review.getBook()),
				review.getRating(), review.getBody(), review.isSpoiler(), photos, 0, 0, false,
				review.isWrittenBy(viewerId), review.getCreatedAt(), review.getEditedAt());
	}

	// 書いた人（機能定義書 3.5）
	public record Author(String username, String displayName) {

		static Author of(User user) {
			return new Author(user.getUsername(), user.getDisplayName());
		}

	}

	// 本（機能定義書 3.5 BookSummary）
	public record BookSummary(long id, String title, List<String> authors, @Nullable String thumbnailUrl) {

		public BookSummary {
			authors = List.copyOf(authors);
		}

		static BookSummary of(Book book) {
			return new BookSummary(book.getId(), book.getTitle(), book.getAuthors(), book.getThumbnailUrl());
		}

	}

	// 写真。url は署名付き URL で、expiresAt を過ぎると使えない（FN-04 PH-8）
	public record Photo(long id, String url, Instant expiresAt) {
	}

}

package com.example.booklog.book;

import java.util.List;

import org.jspecify.annotations.Nullable;

// 一覧に出す本（機能定義書 3.5 BookSummary）。レビューのカードと本棚で使う
public record BookSummary(long id, String title, List<String> authors, @Nullable String thumbnailUrl) {

	public BookSummary {
		authors = List.copyOf(authors);
	}

	public static BookSummary of(Book book) {
		return new BookSummary(book.getId(), book.getTitle(), book.getAuthors(), book.getThumbnailUrl());
	}

}

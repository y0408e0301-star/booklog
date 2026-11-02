package com.example.booklog.book;

import java.util.List;

import org.jspecify.annotations.Nullable;

// 書籍検索の結果の 1 件（FN-02 6 章）。まだ取り込んでいない本もあるため、アプリの本の ID は持たない
public record BookSearchItem(String googleBooksId, String title, List<String> authors, @Nullable String publisher,
		@Nullable String publishedDate, @Nullable String isbn13, @Nullable String thumbnailUrl) {

	public BookSearchItem {
		authors = List.copyOf(authors);
	}

}

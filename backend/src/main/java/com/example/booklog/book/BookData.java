package com.example.booklog.book;

import java.util.List;

import org.jspecify.annotations.Nullable;

// Google Books API の 1 冊を、本アプリで持つ形にそろえたもの（FN-02 5 章の 3〜6、BR-11）。
// 検索の結果の 1 件と、取り込む本の両方に使う。そろえ方は GoogleBooksVolumes
public record BookData(String googleBooksId, @Nullable String isbn13, String title, List<String> authors,
		@Nullable String publisher, @Nullable String publishedDate, @Nullable String description,
		@Nullable String thumbnailUrl) {

	public BookData {
		authors = List.copyOf(authors);
	}

	BookSearchItem toSearchItem() {
		return new BookSearchItem(this.googleBooksId, this.title, this.authors, this.publisher, this.publishedDate,
				this.isbn13, this.thumbnailUrl);
	}

}

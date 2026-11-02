package com.example.booklog.book;

import java.util.List;

// 書籍検索の結果の 1 ページ（20 件まで。FN-02 6 章）。book_search_results.result にもこの形で保存する（BR-12）
public record BookSearchPage(List<BookSearchItem> items, boolean hasNext) {

	public BookSearchPage {
		items = List.copyOf(items);
	}

}

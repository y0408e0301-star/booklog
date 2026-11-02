package com.example.booklog.book;

import java.util.Optional;

// 本の情報を探す・取る部品（基本設計書 2.1「Google Books API を呼ぶ部品」）。
// 検索の結果の使い回しと、呼び出し回数の数え方もこの中に閉じ込める（GoogleBooksCatalog）。
// 使えないとき（止まっている・時間切れ・その日の上限）は ApiException（BOOK_SEARCH_UNAVAILABLE）を投げる
interface BookCatalog {

	BookSearchPage search(BookQuery query, int page);

	// Google Books にない（消された）ときは空
	Optional<BookData> find(String googleBooksId);

}

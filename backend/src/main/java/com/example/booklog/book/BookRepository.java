package com.example.booklog.book;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BookRepository extends JpaRepository<Book, Long> {

	// 取り込み済みかを確かめる（B-6）。google_books_id の一意の制約の索引を使う
	Optional<Book> findByGoogleBooksId(String googleBooksId);

}

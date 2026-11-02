package com.example.booklog.review;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, Long> {

	// 書いた人と本も一緒に読む（ReviewView に表示名・書名を入れるため）
	@Query("select r from Review r join fetch r.author join fetch r.book where r.id = :id")
	Optional<Review> findWithAuthorAndBookById(@Param("id") long id);

	// 本のページのレビューの一覧：その本のレビューを、cursor より古いものから新しい順に（BK-5、FN-09 5 章の 2）。
	// 索引 (book_id, id DESC) を使う
	@Query("""
			select r from Review r join fetch r.author join fetch r.book b
			where b.id = :bookId and r.id < :cursor
			order by r.id desc
			""")
	List<Review> findByBookBefore(@Param("bookId") long bookId, @Param("cursor") long cursor, Limit limit);

	// 同じ本のレビューをもう書いているか（RV-1、BR-2）
	@Query("select count(r) > 0 from Review r where r.author.id = :userId and r.book.id = :bookId")
	boolean existsByAuthorAndBook(@Param("userId") long userId, @Param("bookId") long bookId);

}

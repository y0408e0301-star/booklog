package com.example.booklog.comment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentRepository extends JpaRepository<Comment, Long> {

	// そのレビューのコメントを、cursor より後ろから古い順に（C-3、FN-06 5 章の 1）。索引 (review_id, id) を使う
	@Query("""
			select c from Comment c join fetch c.author
			where c.reviewId = :reviewId and c.id > :cursor
			order by c.id
			""")
	List<Comment> findAfter(@Param("reviewId") long reviewId, @Param("cursor") long cursor, Limit limit);

	// 消すときに、書いた人かを確かめる（C-5）
	@Query("select c from Comment c join fetch c.author where c.id = :id")
	Optional<Comment> findWithAuthorById(@Param("id") long id);

	// 一覧に出すレビューのコメント数を 1 回でまとめて数える（N+1 を作らない。TC-09-6）。0 件のレビューは結果に入らない
	@Query("""
			select new com.example.booklog.comment.CommentCounts$Count(c.reviewId, count(c))
			from Comment c where c.reviewId in :reviewIds group by c.reviewId
			""")
	List<CommentCounts.Count> countByReviewIds(@Param("reviewIds") Collection<Long> reviewIds);

	// レビューがあるか。comment パッケージから review パッケージは使わない（review が comment を使うため）
	@Query(value = "SELECT EXISTS (SELECT 1 FROM reviews WHERE id = :id)", nativeQuery = true)
	boolean reviewExists(@Param("id") long reviewId);

}

package com.example.booklog.review;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewPhotoRepository extends JpaRepository<ReviewPhoto, Long> {

	// 一覧に出すレビューの写真を 1 回でまとめて取る（N+1 を作らない。FN-09 3 章）
	List<ReviewPhoto> findByReviewIdInOrderByReviewIdAscSortOrderAsc(Collection<Long> reviewIds);

	List<ReviewPhoto> findByReviewIdOrderBySortOrderAsc(long reviewId);

	// レビューの写真の置き場所（削除の後で S3 から消すため）
	@Query("select p.storageKey from ReviewPhoto p where p.reviewId = :reviewId")
	List<String> findStorageKeysByReviewId(@Param("reviewId") long reviewId);

}

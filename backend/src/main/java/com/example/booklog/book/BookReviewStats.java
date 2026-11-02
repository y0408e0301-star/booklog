package com.example.booklog.book;

import java.math.BigDecimal;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// 本のページに出す、レビューの平均評価・件数と自分のレビュー（FN-10 BK-3・BK-4）。
// 保存しておかず、開いたときに数える（FN-10 5 章の 2）
@Repository
class BookReviewStats {

	private final JdbcTemplate jdbc;

	BookReviewStats(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// 平均は小数点以下 2 桁目で四捨五入する。0 件のときは null（データ設計書 8.2）
	Stats of(long bookId) {
		return this.jdbc.queryForObject(
				"SELECT count(*) AS review_count, round(avg(rating), 1) AS average_rating FROM reviews WHERE book_id = ?",
				(rs, row) -> {
					BigDecimal average = rs.getBigDecimal("average_rating");
					return new Stats((average == null) ? null : average.doubleValue(), rs.getLong("review_count"));
				}, bookId);
	}

	// 自分がその本に書いたレビューの ID。まだ書いていなければ null
	@Nullable Long myReviewId(long userId, long bookId) {
		List<Long> ids = this.jdbc.queryForList("SELECT id FROM reviews WHERE user_id = ? AND book_id = ?", Long.class,
				userId, bookId);
		return ids.isEmpty() ? null : ids.get(0);
	}

	record Stats(@Nullable Double averageRating, long reviewCount) {
	}

}

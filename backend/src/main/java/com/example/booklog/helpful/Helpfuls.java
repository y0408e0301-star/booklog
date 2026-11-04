package com.example.booklog.helpful;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

// 参考になった（helpfuls。データ設計書 4.5）
@Repository
public class Helpfuls {

	private final NamedParameterJdbcTemplate jdbc;

	public Helpfuls(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// レビューを書いた人の ID。レビューがなければ空
	Optional<Long> authorOf(long reviewId) {
		return this.jdbc.queryForList("SELECT user_id FROM reviews WHERE id = :id", Map.of("id", reviewId), Long.class)
			.stream()
			.findFirst();
	}

	// すでにあるときは何もしない。同時に押されても行は 1 つ（H-3、FN-05 5 章の 1）
	void add(long userId, long reviewId) {
		this.jdbc.update("""
				INSERT INTO helpfuls (user_id, review_id, created_at) VALUES (:userId, :reviewId, now())
				ON CONFLICT DO NOTHING
				""", params(userId, reviewId));
	}

	// ないときは何もしない
	void remove(long userId, long reviewId) {
		this.jdbc.update("DELETE FROM helpfuls WHERE user_id = :userId AND review_id = :reviewId",
				params(userId, reviewId));
	}

	long count(long reviewId) {
		Long count = this.jdbc.queryForObject("SELECT count(*) FROM helpfuls WHERE review_id = :reviewId",
				Map.of("reviewId", reviewId), Long.class);
		return (count == null) ? 0 : count;
	}

	// 一覧に出すレビューの参考になった数を 1 回でまとめて数える（N+1 を作らない。TC-09-6）。0 件のレビューは結果に入らない
	public Map<Long, Long> counts(Collection<Long> reviewIds) {
		Map<Long, Long> counts = new HashMap<>();
		this.jdbc.query("SELECT review_id, count(*) AS n FROM helpfuls WHERE review_id IN (:ids) GROUP BY review_id",
				Map.of("ids", reviewIds), (rs) -> {
					counts.put(rs.getLong("review_id"), rs.getLong("n"));
				});
		return counts;
	}

	// reviewIds のうち、userId が参考になったを付けているレビュー（ReviewView の helpfulByMe）
	public Set<Long> markedBy(long userId, Collection<Long> reviewIds) {
		List<Long> ids = this.jdbc.queryForList(
				"SELECT review_id FROM helpfuls WHERE user_id = :userId AND review_id IN (:ids)",
				Map.of("userId", userId, "ids", reviewIds), Long.class);
		return new HashSet<>(ids);
	}

	private static MapSqlParameterSource params(long userId, long reviewId) {
		return new MapSqlParameterSource().addValue("userId", userId).addValue("reviewId", reviewId);
	}

}

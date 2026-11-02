package com.example.booklog.book;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// Google Books API の日ごとの呼び出し回数（google_books_usage。データ設計書 4.12、BR-13）
@Repository
class GoogleBooksUsage {

	// 1 日に呼べる回数（要件定義書 U-2）。表の CHECK 制約と同じ
	static final int DAILY_LIMIT = 1000;

	private final JdbcTemplate jdbc;

	GoogleBooksUsage(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// 今日の回数を 1 増やし、増やした後の回数を返す。上限に達していて増やせなければ空（データ設計書 8.3）。
	// 1 つの SQL で確かめて増やすので、同時に呼ばれても上限を超えない
	OptionalInt increment(LocalDate today) {
		List<Integer> counts = this.jdbc.queryForList("""
				INSERT INTO google_books_usage (usage_date, call_count, updated_at) VALUES (?, 1, now())
				ON CONFLICT (usage_date) DO UPDATE
				  SET call_count = google_books_usage.call_count + 1, updated_at = now()
				  WHERE google_books_usage.call_count < ?
				RETURNING call_count
				""", Integer.class, today, DAILY_LIMIT);
		return counts.isEmpty() ? OptionalInt.empty() : OptionalInt.of(counts.get(0));
	}

	// before より前の日の行をまとめて消す（1 日 1 回）
	int deleteBefore(LocalDate before) {
		return this.jdbc.update("DELETE FROM google_books_usage WHERE usage_date < ?", before);
	}

}

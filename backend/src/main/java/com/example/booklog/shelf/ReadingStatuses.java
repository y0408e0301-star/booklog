package com.example.booklog.shelf;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// 読書ステータス（reading_statuses。データ設計書 4.8）。ステータスのボタンと本棚（FN-08）は開発計画書 1-7 で足す
@Repository
public class ReadingStatuses {

	private final JdbcTemplate jdbc;

	public ReadingStatuses(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// レビューを投稿したとき、その本が本棚になければ「読了」で入れる。もうあればステータスを変えない（RV-5、BR-10）。
	// 呼んだ側のトランザクションの中で動かす
	public void addReadIfAbsent(long userId, long bookId) {
		this.jdbc.update("""
				INSERT INTO reading_statuses (user_id, book_id, status, updated_at) VALUES (?, ?, 'READ', now())
				ON CONFLICT (user_id, book_id) DO NOTHING
				""", userId, bookId);
	}

}

package com.example.booklog.book;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.json.JsonMapper;

// 書籍検索の結果を 24 時間使い回すための写し（book_search_results。データ設計書 4.11、BR-12）。
// 結果は jsonb なので、JPA ではなく SQL で読み書きする
@Repository
class BookSearchCache {

	private final JdbcTemplate jdbc;

	private final JsonMapper jsonMapper;

	BookSearchCache(JdbcTemplate jdbc, JsonMapper jsonMapper) {
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
	}

	// since より後に取った結果があれば返す
	Optional<BookSearchPage> find(String query, int page, Instant since) {
		List<String> results = this.jdbc.queryForList(
				"SELECT result::text FROM book_search_results WHERE query = ? AND page = ? AND fetched_at > ?",
				String.class, query, page, Timestamp.from(since));
		return results.stream().findFirst().map((json) -> this.jsonMapper.readValue(json, BookSearchPage.class));
	}

	// 入れるか、前の結果を置き換える
	void save(String query, int page, BookSearchPage result, Instant now) {
		this.jdbc.update("""
				INSERT INTO book_search_results (query, page, result, fetched_at) VALUES (?, ?, ?::jsonb, ?)
				ON CONFLICT (query, page) DO UPDATE SET result = EXCLUDED.result, fetched_at = EXCLUDED.fetched_at
				""", query, page, this.jsonMapper.writeValueAsString(result), Timestamp.from(now));
	}

	// before より前に取った結果をまとめて消す（1 日 1 回）
	int deleteFetchedBefore(Instant before) {
		return this.jdbc.update("DELETE FROM book_search_results WHERE fetched_at < ?", Timestamp.from(before));
	}

}

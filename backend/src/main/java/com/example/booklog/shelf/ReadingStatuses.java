package com.example.booklog.shelf;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.booklog.book.BookSummary;

// 読書ステータス（reading_statuses。データ設計書 4.8）と本棚の一覧（FN-08）
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

	// 自分のその本のステータス。本棚になければ null（FN-10 の myStatus）
	public @Nullable ReadingStatus statusOf(long userId, long bookId) {
		List<String> found = this.jdbc.queryForList(
				"SELECT status FROM reading_statuses WHERE user_id = ? AND book_id = ?", String.class, userId, bookId);
		return found.isEmpty() ? null : ReadingStatus.valueOf(found.get(0));
	}

	// 付ける・変える（ST-2）。同じステータスなら updated_at を変えず、本棚の並び順を変えない（FN-08 5 章の 3）
	void set(long userId, long bookId, ReadingStatus status) {
		this.jdbc.update("""
				INSERT INTO reading_statuses (user_id, book_id, status, updated_at) VALUES (?, ?, ?, now())
				ON CONFLICT (user_id, book_id) DO UPDATE SET status = EXCLUDED.status, updated_at = EXCLUDED.updated_at
				WHERE reading_statuses.status <> EXCLUDED.status
				""", userId, bookId, status.name());
	}

	// 外す（ST-3）。なくても何もしない
	void remove(long userId, long bookId) {
		this.jdbc.update("DELETE FROM reading_statuses WHERE user_id = ? AND book_id = ?", userId, bookId);
	}

	// 本棚の一覧（ST-5）。updated_at の新しい順、同じなら本の ID の大きい順に、after より後ろを limit 冊。
	// 星はその人がその本に書いたレビューのもの（なければ null）
	List<ShelfItem> page(long userId, ReadingStatus status, @Nullable ShelfCursor after, int limit) {
		String sql = """
				SELECT b.id, b.title, b.authors, b.thumbnail_url, s.status, s.updated_at, r.rating
				FROM reading_statuses s
				JOIN books b ON b.id = s.book_id
				LEFT JOIN reviews r ON r.user_id = s.user_id AND r.book_id = s.book_id
				WHERE s.user_id = ? AND s.status = ?
				""";
		if (after == null) {
			return this.jdbc.query(sql + " ORDER BY s.updated_at DESC, s.book_id DESC LIMIT ?", ReadingStatuses::item,
					userId, status.name(), limit);
		}
		return this.jdbc.query(
				sql + " AND (s.updated_at, s.book_id) < (?, ?) ORDER BY s.updated_at DESC, s.book_id DESC LIMIT ?",
				ReadingStatuses::item, userId, status.name(), Timestamp.from(after.updatedAt()), after.bookId(), limit);
	}

	private static ShelfItem item(ResultSet rs, int row) throws SQLException {
		Array authors = rs.getArray("authors");
		BookSummary book = new BookSummary(rs.getLong("id"), rs.getString("title"),
				List.of((String[]) authors.getArray()), rs.getString("thumbnail_url"));
		// レビューがなければ null（getInt は 0 を返すので、読んだ直後に wasNull で確かめる）
		int value = rs.getInt("rating");
		Integer rating = rs.wasNull() ? null : value;
		return new ShelfItem(book, ReadingStatus.valueOf(rs.getString("status")), rating,
				rs.getTimestamp("updated_at").toInstant());
	}

	// 本棚の 1 冊（FN-08 6 章）。rating はその人がその本に書いたレビューの星で、なければ null
	record ShelfItem(BookSummary book, ReadingStatus status, @Nullable Integer rating, Instant updatedAt) {
	}

}

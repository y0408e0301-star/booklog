package com.example.booklog.book;

import java.time.Instant;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 本（データ設計書 4.2）。Google Books API から取り込んだ後は変えず、消さない（要件定義書 P-4）
@Entity
@Table(name = "books")
public class Book {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private @Nullable Long id;

	@Column(nullable = false, updatable = false)
	private String googleBooksId;

	// char(13)。13 桁の数字だけを入れるので、空白で埋まることはない
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(updatable = false, length = 13)
	private @Nullable String isbn13;

	@Column(nullable = false, updatable = false)
	private String title;

	// text[]。API が返した順
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(nullable = false, updatable = false)
	private List<String> authors;

	@Column(updatable = false)
	private @Nullable String publisher;

	@Column(updatable = false)
	private @Nullable String publishedDate;

	@Column(updatable = false)
	private @Nullable String description;

	@Column(updatable = false)
	private @Nullable String thumbnailUrl;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected Book() {
		this(new BookData("", null, "", List.of(), null, null, null, null), Instant.EPOCH);
	}

	public Book(BookData data, Instant now) {
		this.googleBooksId = data.googleBooksId();
		this.isbn13 = data.isbn13();
		this.title = data.title();
		this.authors = List.copyOf(data.authors());
		this.publisher = data.publisher();
		this.publishedDate = data.publishedDate();
		this.description = data.description();
		this.thumbnailUrl = data.thumbnailUrl();
		this.createdAt = now;
	}

	public long getId() {
		if (this.id == null) {
			throw new IllegalStateException("まだ保存していない本です");
		}
		return this.id;
	}

	public String getGoogleBooksId() {
		return this.googleBooksId;
	}

	public @Nullable String getIsbn13() {
		return this.isbn13;
	}

	public String getTitle() {
		return this.title;
	}

	public List<String> getAuthors() {
		return List.copyOf(this.authors);
	}

	public @Nullable String getPublisher() {
		return this.publisher;
	}

	public @Nullable String getPublishedDate() {
		return this.publishedDate;
	}

	public @Nullable String getDescription() {
		return this.description;
	}

	public @Nullable String getThumbnailUrl() {
		return this.thumbnailUrl;
	}

}

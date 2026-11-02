package com.example.booklog.review;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.example.booklog.book.Book;
import com.example.booklog.book.BookData;
import com.example.booklog.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

// レビュー（データ設計書 4.3）
@Entity
@Table(name = "reviews")
public class Review {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private @Nullable Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private User author;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "book_id", nullable = false, updatable = false)
	private Book book;

	@Column(nullable = false)
	private short rating;

	@Column(nullable = false)
	private String body;

	@Column(nullable = false)
	private boolean spoiler;

	// 編集しても変えない（FN-03 5 章の 3）
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	private @Nullable Instant editedAt;

	protected Review() {
		this(new User("", "", "", Instant.EPOCH),
				new Book(new BookData("", null, "", List.of(), null, null, null, null), Instant.EPOCH),
				new ReviewInput(1, "", false), Instant.EPOCH);
	}

	// 値は確かめてから渡す（ReviewInput）
	public Review(User author, Book book, ReviewInput input, Instant now) {
		this.author = author;
		this.book = book;
		this.rating = (short) input.rating();
		this.body = input.body();
		this.spoiler = input.spoiler();
		this.createdAt = now;
	}

	// 星・感想・ネタバレありを変え、編集日時を記録する（RV-10）
	public void edit(ReviewInput input, Instant now) {
		this.rating = (short) input.rating();
		this.body = input.body();
		this.spoiler = input.spoiler();
		this.editedAt = now;
	}

	public boolean isWrittenBy(long userId) {
		return this.author.getId() == userId;
	}

	public long getId() {
		if (this.id == null) {
			throw new IllegalStateException("まだ保存していないレビューです");
		}
		return this.id;
	}

	public User getAuthor() {
		return this.author;
	}

	public Book getBook() {
		return this.book;
	}

	public int getRating() {
		return this.rating;
	}

	public String getBody() {
		return this.body;
	}

	public boolean isSpoiler() {
		return this.spoiler;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public @Nullable Instant getEditedAt() {
		return this.editedAt;
	}

}

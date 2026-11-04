package com.example.booklog.book;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.shelf.ReadingStatus;
import com.example.booklog.shelf.ReadingStatuses;

// 書籍検索・本の取り込み（FN-02）と、本の情報（FN-10）
@Service
class BookService {

	// 1〜40 文字の英数字と「-」「_」（FN-02 4 章）
	private static final Pattern GOOGLE_BOOKS_ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");

	private final BookCatalog catalog;

	private final BookRepository books;

	private final BookReviewStats reviewStats;

	private final ReadingStatuses readingStatuses;

	private final Clock clock;

	BookService(BookCatalog catalog, BookRepository books, BookReviewStats reviewStats, ReadingStatuses readingStatuses,
			Clock clock) {
		this.catalog = catalog;
		this.books = books;
		this.reviewStats = reviewStats;
		this.readingStatuses = readingStatuses;
		this.clock = clock;
	}

	// Google Books API を待つ間にトランザクションを開いておかない（使い回しと回数の SQL は 1 文ずつ確定する）
	BookSearchPage search(@Nullable String q, @Nullable Integer page) {
		BookQuery query = BookQuery.parse(q);
		int pageNumber = (page == null) ? 1 : page;
		if (pageNumber < 1 || pageNumber > GoogleBooksCatalog.MAX_PAGE) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		return this.catalog.search(query, pageNumber);
	}

	// 選んだ本を取り込む（B-5・B-6）。もうあれば Google Books API を呼ばずにその本を返す。
	// 同時に同じ本が取り込まれたら、google_books_id の一意の制約で失敗するので、探し直して返す（BR-11）
	ImportResult importBook(@Nullable String googleBooksId) {
		if (googleBooksId == null || !GOOGLE_BOOKS_ID.matcher(googleBooksId).matches()) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		Optional<Book> existing = this.books.findByGoogleBooksId(googleBooksId);
		if (existing.isPresent()) {
			return new ImportResult(existing.get().getId(), false);
		}
		BookData data = this.catalog.find(googleBooksId)
			.orElseThrow(() -> new ApiException(ErrorCode.GOOGLE_BOOK_NOT_FOUND));
		// Google Books API が別の ID（統合された本など）を返しても、選んだ ID で持つ
		BookData selected = new BookData(googleBooksId, data.isbn13(), data.title(), data.authors(), data.publisher(),
				data.publishedDate(), data.description(), data.thumbnailUrl());
		try {
			return new ImportResult(this.books.saveAndFlush(new Book(selected, this.clock.instant())).getId(), true);
		}
		catch (DataIntegrityViolationException ex) {
			return this.books.findByGoogleBooksId(googleBooksId)
				.map((book) -> new ImportResult(book.getId(), false))
				.orElseThrow(() -> ex);
		}
	}

	@Transactional(readOnly = true)
	BookDetail detail(long viewerId, long id) {
		Book book = this.books.findById(id).orElseThrow(() -> new ApiException(ErrorCode.BOOK_NOT_FOUND));
		return BookDetail.of(book, this.reviewStats.of(id), this.reviewStats.myReviewId(viewerId, id),
				this.readingStatuses.statusOf(viewerId, id));
	}

	// created：新しく取り込んだか（201）、もうあったか（200）
	record ImportResult(long id, boolean created) {
	}

	// 本の詳細（FN-10 6 章）。averageRating はレビューが 0 件なら null、myReviewId はまだ書いていなければ null、
	// myStatus は自分の本棚になければ null
	record BookDetail(long id, String title, List<String> authors, @Nullable String publisher,
			@Nullable String publishedDate, @Nullable String isbn13, @Nullable String description,
			@Nullable String thumbnailUrl, @Nullable Double averageRating, long reviewCount, @Nullable Long myReviewId,
			@Nullable ReadingStatus myStatus) {

		BookDetail {
			authors = List.copyOf(authors);
		}

		static BookDetail of(Book book, BookReviewStats.Stats stats, @Nullable Long myReviewId,
				@Nullable ReadingStatus myStatus) {
			return new BookDetail(book.getId(), book.getTitle(), book.getAuthors(), book.getPublisher(),
					book.getPublishedDate(), book.getIsbn13(), book.getDescription(), book.getThumbnailUrl(),
					stats.averageRating(), stats.reviewCount(), myReviewId, myStatus);
		}

	}

}

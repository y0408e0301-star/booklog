package com.example.booklog.review;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.example.booklog.book.Book;
import com.example.booklog.book.BookRepository;
import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.shelf.ReadingStatuses;
import com.example.booklog.storage.PhotoFormat;
import com.example.booklog.storage.PhotoStorage;
import com.example.booklog.storage.StorageDeleter;
import com.example.booklog.user.User;
import com.example.booklog.user.UserRepository;

// レビューの投稿・表示・編集・削除（機能定義書 FN-03）とレビューの写真（FN-04）、
// レビューの一覧（本のページ FN-10 BK-5、タイムライン FN-09）
@Service
class ReviewService {

	private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

	// 一覧の 1 回の件数（機能定義書 3.4）
	static final int PAGE_SIZE = 20;

	// 1 つのレビューの写真の枚数（PH-2）
	static final int PHOTOS_MAX = 4;

	// 1 枚 5MB まで（FN-04 4 章）
	static final long PHOTO_BYTES_MAX = 5L * 1024 * 1024;

	private final ReviewRepository reviews;

	private final ReviewPhotoRepository photos;

	private final BookRepository books;

	private final UserRepository users;

	private final ReadingStatuses readingStatuses;

	private final ReviewViews views;

	private final PhotoStorage storage;

	private final StorageDeleter deleter;

	private final TransactionTemplate transaction;

	private final Clock clock;

	ReviewService(ReviewRepository reviews, ReviewPhotoRepository photos, BookRepository books, UserRepository users,
			ReadingStatuses readingStatuses, ReviewViews views, PhotoStorage storage, StorageDeleter deleter,
			TransactionTemplate transaction, Clock clock) {
		this.reviews = reviews;
		this.photos = photos;
		this.books = books;
		this.users = users;
		this.readingStatuses = readingStatuses;
		this.views = views;
		this.storage = storage;
		this.deleter = deleter;
		this.transaction = transaction;
		this.clock = clock;
	}

	// 写真を S3 に置いてから、レビュー・写真の行・本棚への「読了」の追加を 1 つのトランザクションで保存する
	// （RV-5、FN-03 5 章の 2）。どこかで失敗したら全部やめ、置いた写真を消す（データ設計書 BR-4）。
	// S3 との通信の間はデータベースのトランザクションを開かない
	ReviewView create(long userId, long bookId, ReviewInput input, List<MultipartFile> files) {
		List<Upload> uploads = validPhotos(files, 0);
		// 写真を無駄に置かないよう、先に確かめられることは確かめる
		User author = this.users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
		Book book = this.books.findById(bookId).orElseThrow(() -> new ApiException(ErrorCode.BOOK_NOT_FOUND));
		if (this.reviews.existsByAuthorAndBook(userId, bookId)) {
			throw new ApiException(ErrorCode.REVIEW_EXISTS);
		}
		List<String> keys = put(uploads);
		boolean saved = false;
		try {
			Review review = this.transaction.execute((status) -> {
				Review created;
				try {
					// 同時に同じ本のレビューが投稿されたときは、(user_id, book_id) の一意の制約で失敗する（BR-2）
					created = this.reviews.saveAndFlush(new Review(author, book, input, this.clock.instant()));
				}
				catch (DataIntegrityViolationException ex) {
					throw new ApiException(ErrorCode.REVIEW_EXISTS);
				}
				for (int i = 0; i < uploads.size(); i++) {
					this.photos.save(newPhoto(created.getId(), keys.get(i), uploads.get(i), i));
				}
				this.readingStatuses.addReadIfAbsent(userId, bookId);
				return created;
			});
			saved = true;
			return this.views.of(review, userId);
		}
		finally {
			// データベースへの保存に失敗したら、S3 に置いた写真を消す
			if (!saved) {
				this.deleter.deleteOrRecord(keys);
			}
		}
	}

	@Transactional(readOnly = true)
	ReviewView get(long viewerId, long id) {
		return this.views.of(find(id), viewerId);
	}

	// 自分のレビューだけ。投稿日時・参考になった・コメントは変えない（RV-10）。
	// 写真は keepPhotoIds の写真を前の順のまま前に、足した写真を選んだ順に後ろに並べる（FN-04 5 章の 5）。
	// 足す写真を S3 に置いてからデータベースを変え、外した写真はコミットの後で S3 から消す（BR-4・BR-5）
	ReviewView edit(long userId, long id, ReviewInput input, @Nullable List<Long> keepPhotoIds,
			List<MultipartFile> files) {
		List<Long> keep = (keepPhotoIds == null) ? List.of() : keepPhotoIds;
		findOwn(userId, id);
		validKeep(id, keep);
		List<Upload> uploads = validPhotos(files, keep.size());
		List<String> keys = put(uploads);
		List<String> removed;
		boolean saved = false;
		try {
			removed = this.transaction.execute((status) -> {
				Review review = findOwn(userId, id);
				List<ReviewPhoto> current = validKeep(id, keep);
				List<String> removedKeys = new ArrayList<>();
				int order = 0;
				for (ReviewPhoto photo : current) {
					if (keep.contains(photo.getId())) {
						photo.moveTo(order++);
					}
					else {
						removedKeys.add(photo.getStorageKey());
						this.photos.delete(photo);
					}
				}
				// 並び順の一意の制約はコミットのときに確かめるので、行を足す・消す順は気にしなくてよい（データ設計書 4.4）
				for (int i = 0; i < uploads.size(); i++) {
					this.photos.save(newPhoto(id, keys.get(i), uploads.get(i), order + i));
				}
				review.edit(input, this.clock.instant());
				return removedKeys;
			});
			saved = true;
		}
		finally {
			if (!saved) {
				this.deleter.deleteOrRecord(keys);
			}
		}
		this.deleter.deleteOrRecord(removed);
		return this.views.of(find(id), userId);
	}

	// 自分のレビューだけ。写真・コメント・参考になったも一緒に消え、本と本棚は残す（RV-9、FN-03 5 章の 4）。
	// 先にデータベースから消し、その後で S3 の写真を消す。消せなかった写真は記録して後で消す（BR-5）
	void delete(long userId, long id) {
		List<String> keys = this.transaction.execute((status) -> {
			Review review = findOwn(userId, id);
			List<String> found = this.photos.findStorageKeysByReviewId(id);
			this.reviews.delete(review);
			return found;
		});
		this.deleter.deleteOrRecord(keys);
	}

	// 本のページのレビューの一覧：新しい順に 20 件ずつ（BK-5）。cursor は前回の nextCursor（最後に出したレビューの ID）
	@Transactional(readOnly = true)
	ReviewPage listByBook(long viewerId, long bookId, @Nullable String cursor) {
		if (!this.books.existsById(bookId)) {
			throw new ApiException(ErrorCode.BOOK_NOT_FOUND);
		}
		return page(this.reviews.findByBookBefore(bookId, parseCursor(cursor), Limit.of(PAGE_SIZE + 1)), viewerId);
	}

	// タイムライン（FN-09）：「フォロー中」は自分とフォローしている人、「すべて」は全員のレビューを、新しい順に 20 件ずつ
	@Transactional(readOnly = true)
	ReviewPage timeline(long viewerId, @Nullable String tab, @Nullable String cursor) {
		long before = parseCursor(cursor);
		Limit limit = Limit.of(PAGE_SIZE + 1);
		if ("following".equals(tab)) {
			return page(this.reviews.findFollowingBefore(viewerId, before, limit), viewerId);
		}
		if ("all".equals(tab)) {
			return page(this.reviews.findAllBefore(before, limit), viewerId);
		}
		throw new ApiException(ErrorCode.BAD_REQUEST);
	}

	// 21 件取れたら 20 件を返し、20 件目の ID を次のカーソルにする（21 件目は「続きがあるか」を知るため。データ設計書 8.1）
	private ReviewPage page(List<Review> found, long viewerId) {
		List<Review> page = found.subList(0, Math.min(found.size(), PAGE_SIZE));
		String next = (found.size() > PAGE_SIZE) ? String.valueOf(page.getLast().getId()) : null;
		return new ReviewPage(this.views.of(page, viewerId), next);
	}

	private Review find(long id) {
		return this.reviews.findWithAuthorAndBookById(id)
			.orElseThrow(() -> new ApiException(ErrorCode.REVIEW_NOT_FOUND));
	}

	// 書いた人でなければ 403（BR-7）
	private Review findOwn(long userId, long id) {
		Review review = find(id);
		if (!review.isWrittenBy(userId)) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		return review;
	}

	// 残す写真は、そのレビューの写真の ID だけ（重ねて指定しない）。違えば 400（FN-04 4 章、TC-04-9）。今の写真を並び順に返す
	private List<ReviewPhoto> validKeep(long reviewId, List<Long> keep) {
		List<ReviewPhoto> current = this.photos.findByReviewIdOrderBySortOrderAsc(reviewId);
		Set<Long> ids = new HashSet<>();
		current.forEach((photo) -> ids.add(photo.getId()));
		if (!ids.containsAll(keep) || new HashSet<>(keep).size() != keep.size()) {
			throw new ApiException(ErrorCode.BAD_REQUEST, "keepPhotoIds");
		}
		return current;
	}

	// 枚数 → 大きさ → 形式の順に確かめる（FN-04 4 章）。形式はファイルの先頭のバイトで決め、拡張子・Content-Type は見ない
	private static List<Upload> validPhotos(List<MultipartFile> files, int kept) {
		if (kept + files.size() > PHOTOS_MAX) {
			throw new ApiException(ErrorCode.TOO_MANY_PHOTOS, "photos");
		}
		List<Upload> uploads = new ArrayList<>();
		for (MultipartFile file : files) {
			if (file.getSize() > PHOTO_BYTES_MAX) {
				throw new ApiException(ErrorCode.PHOTO_TOO_LARGE, "photos");
			}
			byte[] data = bytes(file);
			PhotoFormat format = PhotoFormat.detect(data)
				.orElseThrow(() -> new ApiException(ErrorCode.PHOTO_TYPE_INVALID, "photos"));
			uploads.add(new Upload(format, data));
		}
		return uploads;
	}

	// 途中で失敗したら、それまでに置いた写真を消して PHOTO_UPLOAD_FAILED（FN-04 3.1）
	private List<String> put(List<Upload> uploads) {
		List<String> keys = new ArrayList<>();
		try {
			for (Upload upload : uploads) {
				String key = PhotoStorage.newKey("reviews", upload.format());
				this.storage.put(key, upload.format(), upload.data());
				keys.add(key);
			}
			return keys;
		}
		catch (RuntimeException ex) {
			log.error("写真を S3 に置けませんでした", ex);
			this.deleter.deleteOrRecord(keys);
			throw new ApiException(ErrorCode.PHOTO_UPLOAD_FAILED);
		}
	}

	private static ReviewPhoto newPhoto(long reviewId, String key, Upload upload, int sortOrder) {
		return new ReviewPhoto(reviewId, key, upload.format().contentType(), upload.data().length, sortOrder);
	}

	private static byte[] bytes(MultipartFile file) {
		try {
			return file.getBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	// 最初は cursor なし（bigint の最大値より古いもの）。画面はカーソルの中身を読まない（機能定義書 3.4）
	private static long parseCursor(@Nullable String cursor) {
		if (cursor == null) {
			return Long.MAX_VALUE;
		}
		try {
			return Long.parseLong(cursor);
		}
		catch (NumberFormatException ex) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
	}

	private record Upload(PhotoFormat format, byte[] data) {
	}

	// 一覧の形（機能定義書 3.4）。nextCursor が null なら最後まで読んだ
	record ReviewPage(List<ReviewView> items, @Nullable String nextCursor) {

		ReviewPage {
			items = List.copyOf(items);
		}

	}

}

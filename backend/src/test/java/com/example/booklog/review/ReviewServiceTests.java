package com.example.booklog.review;

import static com.example.booklog.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.example.booklog.TestEntities;
import com.example.booklog.book.Book;
import com.example.booklog.book.BookData;
import com.example.booklog.book.BookRepository;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.shelf.ReadingStatuses;
import com.example.booklog.storage.PhotoFormat;
import com.example.booklog.storage.PhotoStorage;
import com.example.booklog.storage.StorageDeleter;
import com.example.booklog.user.User;
import com.example.booklog.user.UserRepository;

import software.amazon.awssdk.core.exception.SdkClientException;

// レビューの投稿・表示・編集・削除（FN-03）と写真（FN-04）、本のレビューの一覧（BK-5）の処理を、
// データベースと S3 を使わずに確かめる
@ExtendWith(MockitoExtension.class)
class ReviewServiceTests {

	private static final ReviewInput INPUT = new ReviewInput(4, "面白かった", false);

	@Mock
	ReviewRepository reviews;

	@Mock
	BookRepository books;

	@Mock
	UserRepository users;

	@Mock
	ReadingStatuses readingStatuses;

	@Mock
	ReviewPhotoRepository photos;

	@Mock
	PhotoStorage storage;

	@Mock
	StorageDeleter deleter;

	private ReviewService service;

	private final User taro = TestEntities.user(1, "taro_y");

	private final User hanako = TestEntities.user(2, "hanako");

	private final Book book = TestEntities.withId(
			new Book(new BookData("g", null, "こころ", List.of("夏目漱石"), null, null, null, null), TestEntities.NOW), 56);

	@BeforeEach
	void setUp() {
		// ReviewViews は本物を使い、写真の行と S3 だけを偽物にする
		this.service = new ReviewService(this.reviews, this.photos, this.books, this.users, this.readingStatuses,
				new ReviewViews(this.photos, this.storage), this.storage, this.deleter,
				TestEntities.immediateTransaction(), TestEntities.fixedClock());
	}

	@Test
	void 投稿すると本棚になければ読了で入れる() {
		when(this.users.findById(1L)).thenReturn(Optional.of(this.taro));
		when(this.books.findById(56L)).thenReturn(Optional.of(this.book));
		when(this.reviews.saveAndFlush(any())).thenAnswer((call) -> TestEntities.withId(call.getArgument(0), 1234));

		ReviewView view = this.service.create(1, 56, INPUT, List.of());

		assertThat(view.id()).isEqualTo(1234);
		assertThat(view.rating()).isEqualTo(4);
		assertThat(view.mine()).isTrue();
		assertThat(view.createdAt()).isEqualTo(TestEntities.NOW);
		assertThat(view.editedAt()).isNull();
		assertThat(view.book()).isEqualTo(new ReviewView.BookSummary(56, "こころ", List.of("夏目漱石"), null));
		verify(this.readingStatuses).addReadIfAbsent(1, 56);
	}

	// TC-03-3
	@Test
	void 同じ本のレビューがあれば409で本棚も変えない() {
		when(this.users.findById(1L)).thenReturn(Optional.of(this.taro));
		when(this.books.findById(56L)).thenReturn(Optional.of(this.book));
		when(this.reviews.existsByAuthorAndBook(1, 56)).thenReturn(true);

		assertApiError(() -> this.service.create(1, 56, INPUT, List.of()), ErrorCode.REVIEW_EXISTS);
		verify(this.reviews, never()).saveAndFlush(any());
		verify(this.readingStatuses, never()).addReadIfAbsent(anyLong(), anyLong());
	}

	// TC-03-3：同時に投稿されて一意の制約で失敗したとき
	@Test
	void 同時に投稿されたら409() {
		when(this.users.findById(1L)).thenReturn(Optional.of(this.taro));
		when(this.books.findById(56L)).thenReturn(Optional.of(this.book));
		when(this.reviews.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

		assertApiError(() -> this.service.create(1, 56, INPUT, List.of()), ErrorCode.REVIEW_EXISTS);
		verify(this.readingStatuses, never()).addReadIfAbsent(anyLong(), anyLong());
	}

	@Test
	void ない本には書けない() {
		when(this.users.findById(1L)).thenReturn(Optional.of(this.taro));
		when(this.books.findById(999L)).thenReturn(Optional.empty());
		assertApiError(() -> this.service.create(1, 999, INPUT, List.of()), ErrorCode.BOOK_NOT_FOUND);
	}

	@Test
	void 退会した人のJWTでは書けない() {
		when(this.users.findById(9L)).thenReturn(Optional.empty());
		assertApiError(() -> this.service.create(9, 56, INPUT, List.of()), ErrorCode.UNAUTHENTICATED);
	}

	@Test
	void 編集すると編集日時を記録し投稿日時は変えない() {
		Review review = review(1234, this.taro);
		when(this.reviews.findWithAuthorAndBookById(1234)).thenReturn(Optional.of(review));

		ReviewView view = this.service.edit(1, 1234, new ReviewInput(2, "読み直した", true), null, List.of());

		assertThat(view.rating()).isEqualTo(2);
		assertThat(view.body()).isEqualTo("読み直した");
		assertThat(view.spoiler()).isTrue();
		assertThat(view.editedAt()).isEqualTo(TestEntities.NOW);
		assertThat(view.createdAt()).isEqualTo(TestEntities.NOW.minusSeconds(60));
	}

	// TC-03-6
	@Test
	void 他人のレビューは編集も削除もできない() {
		when(this.reviews.findWithAuthorAndBookById(1234)).thenReturn(Optional.of(review(1234, this.hanako)));

		assertApiError(() -> this.service.edit(1, 1234, INPUT, null, List.of()), ErrorCode.FORBIDDEN);
		assertApiError(() -> this.service.delete(1, 1234), ErrorCode.FORBIDDEN);
		verify(this.reviews, never()).delete(any());
	}

	@Test
	void 自分のレビューを削除し写真は後でS3から消す() {
		Review review = review(1234, this.taro);
		when(this.reviews.findWithAuthorAndBookById(1234)).thenReturn(Optional.of(review));
		when(this.photos.findStorageKeysByReviewId(1234)).thenReturn(List.of("reviews/a.png"));
		this.service.delete(1, 1234);
		InOrder order = inOrder(this.reviews, this.deleter);
		order.verify(this.reviews).delete(review);
		order.verify(this.deleter).deleteOrRecord(List.of("reviews/a.png"));
	}

	// ---- 写真（FN-04）----

	@Test
	void 写真をS3に置いてから行を保存し選んだ順に並べる() {
		when(this.users.findById(1L)).thenReturn(Optional.of(this.taro));
		when(this.books.findById(56L)).thenReturn(Optional.of(this.book));
		when(this.reviews.saveAndFlush(any())).thenAnswer((call) -> TestEntities.withId(call.getArgument(0), 1234));

		this.service.create(1, 56, INPUT, List.of(file(PNG), file(JPEG)));

		ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
		InOrder order = inOrder(this.storage, this.reviews);
		order.verify(this.storage).put(keys.capture(), eq(PhotoFormat.PNG), any());
		order.verify(this.storage).put(keys.capture(), eq(PhotoFormat.JPEG), any());
		order.verify(this.reviews).saveAndFlush(any());
		ArgumentCaptor<ReviewPhoto> saved = ArgumentCaptor.forClass(ReviewPhoto.class);
		verify(this.photos, times(2)).save(saved.capture());
		assertThat(saved.getAllValues()).extracting(ReviewPhoto::getStorageKey).isEqualTo(keys.getAllValues());
		assertThat(saved.getAllValues()).extracting(ReviewPhoto::getSortOrder).containsExactly(0, 1);
		assertThat(saved.getAllValues()).extracting(ReviewPhoto::getContentType)
			.containsExactly("image/png", "image/jpeg");
		assertThat(keys.getValue()).matches("reviews/[0-9a-f-]{36}[.]jpg");
		verify(this.deleter, never()).deleteOrRecord(any());
	}

	// TC-04-1
	@Test
	void 五枚以上はTOO_MANY_PHOTOSで何も置かない() {
		List<MultipartFile> five = List.of(file(PNG), file(PNG), file(PNG), file(PNG), file(PNG));
		assertApiError(() -> this.service.create(1, 56, INPUT, five), ErrorCode.TOO_MANY_PHOTOS, "photos");
		verifyNoInteractions(this.storage, this.reviews);
	}

	// TC-04-2
	@Test
	void 五MBちょうどは添えられ一バイト超えるとPHOTO_TOO_LARGE() {
		byte[] large = withHeader((int) ReviewService.PHOTO_BYTES_MAX + 1);
		assertApiError(() -> this.service.create(1, 56, INPUT, List.of(file(large))), ErrorCode.PHOTO_TOO_LARGE,
				"photos");

		byte[] exact = withHeader((int) ReviewService.PHOTO_BYTES_MAX);
		when(this.users.findById(1L)).thenReturn(Optional.of(this.taro));
		when(this.books.findById(56L)).thenReturn(Optional.of(this.book));
		when(this.reviews.saveAndFlush(any())).thenAnswer((call) -> TestEntities.withId(call.getArgument(0), 1234));
		this.service.create(1, 56, INPUT, List.of(file(exact)));
		verify(this.storage).put(any(), eq(PhotoFormat.PNG), eq(exact));
	}

	// TC-04-3：拡張子ではなく中身で決める
	@Test
	void 写真でないファイルはPHOTO_TYPE_INVALID() {
		MockMultipartFile text = new MockMultipartFile("photos", "photo.jpg", "image/jpeg",
				"テキスト".getBytes(StandardCharsets.UTF_8));
		assertApiError(() -> this.service.create(1, 56, INPUT, List.of(text)), ErrorCode.PHOTO_TYPE_INVALID, "photos");
		verifyNoInteractions(this.storage);
	}

	// TC-04-4
	@Test
	void S3への保存が途中で失敗したら置いた写真を消してPHOTO_UPLOAD_FAILED() {
		when(this.users.findById(1L)).thenReturn(Optional.of(this.taro));
		when(this.books.findById(56L)).thenReturn(Optional.of(this.book));
		doNothing().doThrow(SdkClientException.create("S3 につながらない")).when(this.storage).put(any(), any(), any());

		assertApiError(() -> this.service.create(1, 56, INPUT, List.of(file(PNG), file(PNG))),
				ErrorCode.PHOTO_UPLOAD_FAILED);
		ArgumentCaptor<List<String>> deleted = listCaptor();
		verify(this.deleter).deleteOrRecord(deleted.capture());
		assertThat(deleted.getValue()).hasSize(1);
		verify(this.reviews, never()).saveAndFlush(any());
	}

	// TC-04-4：データベースへの保存に失敗したときも、置いた写真を消す
	@Test
	void 行の保存に失敗したら置いた写真を消す() {
		when(this.users.findById(1L)).thenReturn(Optional.of(this.taro));
		when(this.books.findById(56L)).thenReturn(Optional.of(this.book));
		when(this.reviews.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

		assertApiError(() -> this.service.create(1, 56, INPUT, List.of(file(PNG))), ErrorCode.REVIEW_EXISTS);
		ArgumentCaptor<List<String>> deleted = listCaptor();
		verify(this.deleter).deleteOrRecord(deleted.capture());
		assertThat(deleted.getValue()).hasSize(1).allMatch((key) -> key.startsWith("reviews/"));
	}

	// TC-04-5（FN-04 5 章の 5）
	@Test
	void 編集では残した写真を前の順のまま前に足した写真を後ろに並べ外した写真はコミットの後で消す() {
		Review review = review(1234, this.taro);
		when(this.reviews.findWithAuthorAndBookById(1234)).thenReturn(Optional.of(review));
		ReviewPhoto first = photo(10, 0, "reviews/first.png");
		ReviewPhoto second = photo(11, 1, "reviews/second.png");
		ReviewPhoto third = photo(12, 2, "reviews/third.png");
		when(this.photos.findByReviewIdOrderBySortOrderAsc(1234)).thenReturn(List.of(first, second, third));

		// 3 枚のうち 1 枚目を外し、3 枚目・2 枚目の順に「残す」と送っても、前の順のまま並べる
		this.service.edit(1, 1234, INPUT, List.of(12L, 11L), List.of(file(PNG)));

		verify(this.photos).delete(first);
		assertThat(second.getSortOrder()).isZero();
		assertThat(third.getSortOrder()).isEqualTo(1);
		ArgumentCaptor<ReviewPhoto> added = ArgumentCaptor.forClass(ReviewPhoto.class);
		verify(this.photos).save(added.capture());
		assertThat(added.getValue().getSortOrder()).isEqualTo(2);
		verify(this.deleter).deleteOrRecord(List.of("reviews/first.png"));
	}

	// TC-04-1：3 枚のレビューに 2 枚足そうとすると 5 枚になる
	@Test
	void 残す写真と足す写真で五枚以上ならTOO_MANY_PHOTOS() {
		when(this.reviews.findWithAuthorAndBookById(1234)).thenReturn(Optional.of(review(1234, this.taro)));
		when(this.photos.findByReviewIdOrderBySortOrderAsc(1234))
			.thenReturn(List.of(photo(10, 0, "a"), photo(11, 1, "b"), photo(12, 2, "c")));
		assertApiError(() -> this.service.edit(1, 1234, INPUT, List.of(10L, 11L, 12L), List.of(file(PNG), file(PNG))),
				ErrorCode.TOO_MANY_PHOTOS, "photos");
		verifyNoInteractions(this.storage);
	}

	// TC-04-9
	@Test
	void ほかのレビューの写真のIDを残すと400() {
		when(this.reviews.findWithAuthorAndBookById(1234)).thenReturn(Optional.of(review(1234, this.taro)));
		when(this.photos.findByReviewIdOrderBySortOrderAsc(1234)).thenReturn(List.of(photo(10, 0, "a")));
		assertApiError(() -> this.service.edit(1, 1234, INPUT, List.of(99L), List.of()), ErrorCode.BAD_REQUEST,
				"keepPhotoIds");
		assertApiError(() -> this.service.edit(1, 1234, INPUT, List.of(10L, 10L), List.of()), ErrorCode.BAD_REQUEST,
				"keepPhotoIds");
	}

	// 先頭のバイト：PNG と JPEG（FN-04 4 章）
	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1 };

	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1 };

	// PNG の先頭のバイトを持つ、size バイトのファイル
	private static byte[] withHeader(int size) {
		byte[] data = new byte[size];
		System.arraycopy(PNG, 0, data, 0, PNG.length);
		return data;
	}

	private static MultipartFile file(byte[] data) {
		return new MockMultipartFile("photos", "photo", null, data);
	}

	private static ReviewPhoto photo(long id, int sortOrder, String key) {
		return TestEntities.withId(new ReviewPhoto(1234, key, "image/png", 10, sortOrder), id);
	}

	@SuppressWarnings("unchecked")
	private static ArgumentCaptor<List<String>> listCaptor() {
		return ArgumentCaptor.forClass(List.class);
	}

	// TC-03-12
	@Test
	void 削除されたレビューはM20() {
		when(this.reviews.findWithAuthorAndBookById(1234)).thenReturn(Optional.empty());
		assertApiError(() -> this.service.get(1, 1234), ErrorCode.REVIEW_NOT_FOUND);
		assertApiError(() -> this.service.edit(1, 1234, INPUT, null, List.of()), ErrorCode.REVIEW_NOT_FOUND);
		assertApiError(() -> this.service.delete(1, 1234), ErrorCode.REVIEW_NOT_FOUND);
	}

	@Test
	void ほかの人のレビューはmineがfalse() {
		when(this.reviews.findWithAuthorAndBookById(1234)).thenReturn(Optional.of(review(1234, this.hanako)));
		assertThat(this.service.get(1, 1234).mine()).isFalse();
	}

	// BK-5：21 件取れたら 20 件を返し、最後の ID を次のカーソルにする
	@Test
	void 一覧は20件ずつで続きがあれば最後のIDを返す() {
		when(this.books.existsById(56L)).thenReturn(true);
		List<Review> found = LongStream.rangeClosed(1, 21)
			.map((i) -> 100 - i)
			.mapToObj((id) -> review(id, this.taro))
			.toList();
		when(this.reviews.findByBookBefore(56, Long.MAX_VALUE, Limit.of(21))).thenReturn(found);

		ReviewService.ReviewPage page = this.service.listByBook(1, 56, null);

		assertThat(page.items()).hasSize(20);
		assertThat(page.items().getFirst().id()).isEqualTo(99);
		assertThat(page.nextCursor()).isEqualTo("80");
	}

	@Test
	void 最後まで読んだら次のカーソルはない() {
		when(this.books.existsById(56L)).thenReturn(true);
		when(this.reviews.findByBookBefore(eq(56L), eq(80L), any())).thenReturn(List.of(review(79, this.taro)));
		assertThat(this.service.listByBook(1, 56, "80").nextCursor()).isNull();
	}

	@Test
	void カーソルが数でなければBAD_REQUEST() {
		when(this.books.existsById(56L)).thenReturn(true);
		assertApiError(() -> this.service.listByBook(1, 56, "abc"), ErrorCode.BAD_REQUEST);
	}

	@Test
	void ない本の一覧はM15() {
		when(this.books.existsById(999L)).thenReturn(false);
		assertApiError(() -> this.service.listByBook(1, 999, null), ErrorCode.BOOK_NOT_FOUND);
	}

	// FN-09 T-1
	@Test
	void タイムラインはタブでフォロー中とすべてを分ける() {
		when(this.reviews.findFollowingBefore(1, Long.MAX_VALUE, Limit.of(21)))
			.thenReturn(List.of(review(3, this.taro)));
		when(this.reviews.findAllBefore(80, Limit.of(21))).thenReturn(List.of(review(79, this.hanako)));

		assertThat(this.service.timeline(1, "following", null).items()).extracting(ReviewView::id).containsExactly(3L);
		assertThat(this.service.timeline(1, "all", "80").items()).extracting(ReviewView::id).containsExactly(79L);
	}

	@Test
	void タブがfollowingかall以外ならBAD_REQUEST() {
		assertApiError(() -> this.service.timeline(1, null, null), ErrorCode.BAD_REQUEST);
		assertApiError(() -> this.service.timeline(1, "mine", null), ErrorCode.BAD_REQUEST);
		assertApiError(() -> this.service.timeline(1, "all", "x"), ErrorCode.BAD_REQUEST);
	}

	private Review review(long id, User author) {
		return TestEntities.withId(new Review(author, this.book, INPUT, TestEntities.NOW.minusSeconds(60)), id);
	}

}

package com.example.booklog.review;

import static com.example.booklog.ControllerTestSupport.assertError;
import static com.example.booklog.ControllerTestSupport.loggedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.testcontainers.containers.MinIOContainer;

import com.example.booklog.MutableClock;
import com.example.booklog.TestcontainersConfiguration;
import com.example.booklog.common.ErrorCode;
import com.jayway.jsonpath.JsonPath;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

// 写真（機能定義書 FN-04）を、API とテスト用の S3（MinIO）を通して確かめる。10 章の「テストの観点」のうち、
// サーバー側で確かめられるもの。S3 やデータベースへの保存に失敗するときの観点は PhotoFailureTests
@SpringBootTest
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@AutoConfigureMockMvc
public class PhotoApiTests {

	public static final int FIVE_MB = 5 * 1024 * 1024;

	@Autowired
	MockMvcTester mvc;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MinIOContainer minio;

	private S3Client s3;

	private long taro;

	private long hanako;

	private long book;

	@BeforeEach
	void setUp() {
		this.jdbc.execute("TRUNCATE users, books, reviews, reading_statuses, follows, storage_deletions "
				+ "RESTART IDENTITY CASCADE");
		this.clock.reset();
		this.s3 = TestcontainersConfiguration.s3Client(this.minio);
		emptyBucket(this.s3);
		this.taro = user(this.jdbc, "taro_y");
		this.hanako = user(this.jdbc, "hanako");
		this.book = book(this.jdbc, "a");
	}

	@AfterEach
	void tearDown() {
		this.s3.close();
	}

	// ---- 写真つきの投稿（PH-1・PH-5・PH-6）----

	@Test
	void 写真を選んだ順に保存しContentTypeつきでS3に置き署名付きURLで読める() throws Exception {
		List<MockMultipartFile> files = List.of(photo("a.jpg", jpeg(100)), photo("b.png", png(200)),
				photo("c.webp", webp(300)));
		MvcTestResult result = create(this.taro, this.book, files);

		assertThat(result).hasStatus(HttpStatus.CREATED);
		List<Map<String, Object>> photos = JsonPath.read(content(result), "$.photos");
		assertThat(photos).hasSize(3);
		assertThat(this.jdbc.queryForList("SELECT content_type FROM review_photos ORDER BY sort_order", String.class))
			.containsExactly("image/jpeg", "image/png", "image/webp");
		List<String> keys = keys();
		// 元のファイル名は使わない（5 章の 1）
		assertThat(keys).allMatch(
				(key) -> key.matches("reviews/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z]+"));
		assertThat(keys.get(0)).endsWith(".jpg");
		assertThat(keys.get(2)).endsWith(".webp");

		HeadObjectResponse head = this.s3
			.headObject((request) -> request.bucket(TestcontainersConfiguration.BUCKET).key(keys.get(1)));
		assertThat(head.contentType()).isEqualTo("image/png");

		// 署名付き URL は 15 分で切れ（期限は AWS SDK が実際の時刻で決める）、その URL で中身を読める
		Map<String, Object> second = photos.get(1);
		assertThat(Instant.parse((String) second.get("expiresAt")))
			.isBetween(Instant.now().plus(Duration.ofMinutes(14)), Instant.now().plus(Duration.ofMinutes(15)));
		HttpResponse<byte[]> got = httpGet((String) second.get("url"));
		assertThat(got.statusCode()).isEqualTo(200);
		assertThat(got.headers().firstValue("Content-Type")).hasValue("image/png");
		assertThat(got.body()).isEqualTo(png(200));
	}

	// TC-04-7（N-16）
	@Test
	void バケットのURLを署名なしで開いても写真は見られない() throws Exception {
		create(this.taro, this.book, List.of(photo("a.png", png(10))));
		HttpResponse<byte[]> got = httpGet(
				this.minio.getS3URL() + "/" + TestcontainersConfiguration.BUCKET + "/" + keys().getFirst());
		assertThat(got.statusCode()).isEqualTo(403);
	}

	// ---- 入力の確認（4 章）----

	// TC-04-1（BR-3）
	@Test
	void 写真5枚は400のTOO_MANY_PHOTOSでレビューも写真もできない() {
		List<MockMultipartFile> files = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			files.add(photo(i + ".png", png(1)));
		}
		assertError(create(this.taro, this.book, files), ErrorCode.TOO_MANY_PHOTOS, "photos");
		assertNothingSaved();
	}

	// TC-04-2
	@Test
	void 写真5MBちょうどは添えられ5MBより1バイト大きいと413のPHOTO_TOO_LARGE() {
		assertError(create(this.taro, this.book, List.of(photo("big.jpg", jpeg(FIVE_MB - 3 + 1)))),
				ErrorCode.PHOTO_TOO_LARGE, "photos");
		assertNothingSaved();
		assertThat(create(this.taro, this.book, List.of(photo("big.jpg", jpeg(FIVE_MB - 3)))))
			.hasStatus(HttpStatus.CREATED);
		assertThat(this.jdbc.queryForObject("SELECT byte_size FROM review_photos", Integer.class)).isEqualTo(FIVE_MB);
	}

	// TC-04-3
	@Test
	void 拡張子をjpgに変えたテキストやPDFやGIFは400のPHOTO_TYPE_INVALID() {
		for (byte[] data : List.of("テキスト".getBytes(StandardCharsets.UTF_8),
				"%PDF-1.7".getBytes(StandardCharsets.US_ASCII), "GIF89a".getBytes(StandardCharsets.US_ASCII))) {
			assertError(create(this.taro, this.book, List.of(photo("fake.jpg", data))), ErrorCode.PHOTO_TYPE_INVALID,
					"photos");
		}
		assertNothingSaved();
	}

	// ---- 編集（PH-4、5 章の 5）----

	// TC-04-5
	// N-10
	@Test
	void 編集で一枚外して一枚足すと外した写真がS3から消え並び順が前の順のまま() {
		long id = id(create(this.taro, this.book,
				List.of(photo("1.png", png(1)), photo("2.png", png(2)), photo("3.png", png(3)))));
		List<Long> photoIds = photoIds();
		List<String> before = keys();

		MvcTestResult edited = edit(this.taro, id, List.of(photoIds.get(2), photoIds.get(1)),
				List.of(photo("4.jpg", jpeg(4))));

		assertThat(edited).hasStatusOk();
		// 残した 2 枚目・3 枚目が前の順のまま前に、足した写真が後ろに
		List<Number> ids = JsonPath.read(content(edited), "$.photos[*].id");
		assertThat(ids.subList(0, 2)).extracting(Number::longValue).containsExactly(photoIds.get(1), photoIds.get(2));
		assertThat(this.jdbc.queryForList("SELECT content_type FROM review_photos ORDER BY sort_order", String.class))
			.containsExactly("image/png", "image/png", "image/jpeg");
		assertThat(exists(before.get(0))).isFalse();
		assertThat(exists(before.get(1))).isTrue();
		assertThat(bucketKeys()).hasSize(3);
	}

	// TC-04-1：3 枚のレビューに 2 枚足そうとすると 5 枚になる
	@Test
	void 編集で残す写真と足す写真が5枚以上なら400() {
		long id = id(create(this.taro, this.book,
				List.of(photo("1.png", png(1)), photo("2.png", png(2)), photo("3.png", png(3)))));
		assertError(edit(this.taro, id, photoIds(), List.of(photo("4.png", png(4)), photo("5.png", png(5)))),
				ErrorCode.TOO_MANY_PHOTOS, "photos");
		assertThat(bucketKeys()).hasSize(3);
	}

	// TC-04-9
	@Test
	void ほかの人のレビューの写真のIDを残すと400() {
		create(this.hanako, this.book, List.of(photo("h.png", png(1))));
		long hanakoPhoto = photoIds().getFirst();
		long id = id(create(this.taro, book(this.jdbc, "b"), List.of()));
		assertError(edit(this.taro, id, List.of(hanakoPhoto), List.of()), ErrorCode.BAD_REQUEST, "keepPhotoIds");
	}

	// ---- 削除（5 章の 6）----

	// TC-04-6
	// N-10
	@Test
	void レビューを削除すると写真の行とS3のファイルも消える() {
		long id = id(create(this.taro, this.book, List.of(photo("a.png", png(1)), photo("b.png", png(2)))));
		assertThat(this.mvc.delete().uri("/api/reviews/{id}", id).with(loggedIn(this.taro)).with(csrf()))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM review_photos", Integer.class)).isZero();
		assertThat(bucketKeys()).isEmpty();
	}

	// ---- 表示と URL の取り直し（PH-6・PH-8）----

	@Test
	void 一覧と詳細のレビューに写真が選んだ順で出る() {
		long id = id(create(this.taro, this.book, List.of(photo("a.png", png(1)), photo("b.jpg", jpeg(2)))));
		List<Long> expected = photoIds();
		for (String uri : List.of("/api/reviews/" + id, "/api/timeline?tab=all",
				"/api/books/" + this.book + "/reviews")) {
			String body = content(this.mvc.get().uri(uri).with(loggedIn(this.hanako)).exchange());
			List<Number> ids = JsonPath.read(body,
					uri.startsWith("/api/reviews") ? "$.photos[*].id" : "$.items[0].photos[*].id");
			assertThat(ids).extracting(Number::longValue).isEqualTo(expected);
		}
	}

	// TC-04-8（FN-04 6 章）
	@Test
	void URLを取り直せて削除された写真のIDは結果に入らない() throws Exception {
		create(this.taro, this.book, List.of(photo("a.png", png(7))));
		long photoId = photoIds().getFirst();

		MvcTestResult result = urls(this.hanako, "{\"photoIds\":[" + photoId + ",9999]}");
		assertThat(result).hasStatusOk();
		List<Number> ids = JsonPath.read(content(result), "$.items[*].id");
		assertThat(ids).extracting(Number::longValue).containsExactly(photoId);
		String url = JsonPath.read(content(result), "$.items[0].url");
		assertThat(httpGet(url).body()).isEqualTo(png(7));
	}

	@Test
	void URLの取り直しは50個までで51個は400() {
		StringBuilder ids = new StringBuilder();
		for (int i = 1; i <= 51; i++) {
			ids.append(i).append(i < 51 ? "," : "");
		}
		assertError(urls(this.taro, "{\"photoIds\":[" + ids + "]}"), ErrorCode.BAD_REQUEST, "photoIds");
		assertError(urls(this.taro, "{}"), ErrorCode.BAD_REQUEST, "photoIds");
	}

	@Test
	void URLの取り直しはログインしていないと401() {
		assertThat(this.mvc.post()
			.uri("/api/photos/urls")
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"photoIds\":[1]}")).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	// ---- 準備 ----

	MvcTestResult create(long userId, long bookId, List<MockMultipartFile> files) {
		MockMultipartHttpServletRequestBuilder request = multipart("/api/books/{bookId}/reviews", bookId);
		files.forEach(request::file);
		return this.mvc.perform(request.param("rating", "4").param("body", "感想").with(loggedIn(userId)).with(csrf()));
	}

	private MvcTestResult edit(long userId, long id, List<Long> keep, List<MockMultipartFile> files) {
		MockMultipartHttpServletRequestBuilder request = multipart(HttpMethod.PATCH, "/api/reviews/{id}", id);
		files.forEach(request::file);
		keep.forEach((photoId) -> request.param("keepPhotoIds", String.valueOf(photoId)));
		return this.mvc.perform(request.param("rating", "3").param("body", "編集").with(loggedIn(userId)).with(csrf()));
	}

	private MvcTestResult urls(long userId, String json) {
		return this.mvc.post()
			.uri("/api/photos/urls")
			.with(loggedIn(userId))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json)
			.exchange();
	}

	private void assertNothingSaved() {
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM reviews", Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM review_photos", Integer.class)).isZero();
		assertThat(bucketKeys()).isEmpty();
	}

	private List<String> keys() {
		return this.jdbc.queryForList("SELECT storage_key FROM review_photos ORDER BY review_id, sort_order",
				String.class);
	}

	private List<Long> photoIds() {
		return this.jdbc.queryForList("SELECT id FROM review_photos ORDER BY review_id, sort_order", Long.class);
	}

	private boolean exists(String key) {
		try {
			this.s3.headObject((request) -> request.bucket(TestcontainersConfiguration.BUCKET).key(key));
			return true;
		}
		catch (NoSuchKeyException ex) {
			return false;
		}
	}

	List<String> bucketKeys() {
		return this.s3.listObjectsV2((request) -> request.bucket(TestcontainersConfiguration.BUCKET))
			.contents()
			.stream()
			.map(S3Object::key)
			.toList();
	}

	static long user(JdbcTemplate jdbc, String username) {
		return jdbc.queryForObject(
				"INSERT INTO users (username, display_name, password_hash) VALUES (?, ?, 'hash') RETURNING id",
				Long.class, username, username + "の表示名");
	}

	static long book(JdbcTemplate jdbc, String googleBooksId) {
		return jdbc.queryForObject("INSERT INTO books (google_books_id, title) VALUES (?, '本') RETURNING id",
				Long.class, googleBooksId);
	}

	static void emptyBucket(S3Client s3) {
		s3.listObjectsV2((request) -> request.bucket(TestcontainersConfiguration.BUCKET))
			.contents()
			.forEach((object) -> s3
				.deleteObject((request) -> request.bucket(TestcontainersConfiguration.BUCKET).key(object.key())));
	}

	static MockMultipartFile photo(String name, byte[] data) {
		return new MockMultipartFile("photos", name, "image/jpeg", data);
	}

	static String content(MvcTestResult result) {
		return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

	static long id(MvcTestResult result) {
		return JsonPath.<Number>read(content(result), "$.id").longValue();
	}

	static HttpResponse<byte[]> httpGet(String url) throws IOException, InterruptedException {
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(HttpRequest.newBuilder(URI.create(url)).build(),
					HttpResponse.BodyHandlers.ofByteArray());
		}
	}

	// 先頭のバイト（FN-04 4 章）を付けて、全部で size バイトにする
	static byte[] jpeg(int size) {
		return withMagic(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF }, size + 3);
	}

	static byte[] png(int size) {
		return withMagic(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A }, size + 8);
	}

	static byte[] webp(int size) {
		return withMagic(new byte[] { 'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P' }, size + 12);
	}

	private static byte[] withMagic(byte[] magic, int size) {
		byte[] data = new byte[size];
		System.arraycopy(magic, 0, data, 0, magic.length);
		for (int i = magic.length; i < size; i++) {
			data[i] = (byte) (i % 251);
		}
		return data;
	}

}

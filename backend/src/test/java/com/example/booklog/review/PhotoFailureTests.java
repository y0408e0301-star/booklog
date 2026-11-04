package com.example.booklog.review;

import static com.example.booklog.ControllerTestSupport.assertError;
import static com.example.booklog.ControllerTestSupport.loggedIn;
import static com.example.booklog.review.PhotoApiTests.photo;
import static com.example.booklog.review.PhotoApiTests.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.MinIOContainer;

import com.example.booklog.MutableClock;
import com.example.booklog.TestcontainersConfiguration;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.storage.PhotoStorage;
import com.example.booklog.storage.StorageDeleter;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

// S3 やデータベースへの保存・削除に失敗したときの動き（データ設計書 BR-4・BR-5、機能定義書 FN-04 3.1・10 章）。
// S3 の失敗は PhotoStorage を差し替えて起こす（Spring のコンテキストが変わるので、PhotoApiTests とは分ける）
@SpringBootTest
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@AutoConfigureMockMvc
class PhotoFailureTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MinIOContainer minio;

	@Autowired
	StorageDeleter deleter;

	@MockitoSpyBean
	PhotoStorage storage;

	private S3Client s3;

	private long taro;

	private long book;

	@BeforeEach
	void setUp() {
		this.jdbc
			.execute("TRUNCATE users, books, reviews, reading_statuses, storage_deletions RESTART IDENTITY CASCADE");
		this.s3 = TestcontainersConfiguration.s3Client(this.minio);
		PhotoApiTests.emptyBucket(this.s3);
		this.taro = PhotoApiTests.user(this.jdbc, "taro_y");
		this.book = PhotoApiTests.book(this.jdbc, "a");
	}

	@AfterEach
	void tearDown() {
		this.jdbc.execute("DROP TRIGGER IF EXISTS fail_review_photos ON review_photos");
		this.jdbc.execute("DROP FUNCTION IF EXISTS fail_review_photos()");
		this.s3.close();
	}

	// TC-04-4
	@Test
	void S3への保存が途中で失敗したら500のPHOTO_UPLOAD_FAILEDでレビューも写真も残らない() {
		// 1 枚目は置けて、2 枚目で S3 が止まった
		doCallRealMethod().doThrow(SdkClientException.create("S3 につながらない"))
			.when(this.storage)
			.put(anyString(), any(), any());

		MvcTestResult result = create(List.of(photo("a.png", png(1)), photo("b.png", png(2))));

		assertError(result, ErrorCode.PHOTO_UPLOAD_FAILED);
		assertNothingLeft();
	}

	// TC-04-4（BR-4）：写真を置いた後でデータベースへの保存に失敗したら、置いた写真を消す
	@Test
	void 写真の行の保存に失敗したらレビューも写真も残らない() {
		this.jdbc.execute("""
				CREATE FUNCTION fail_review_photos() RETURNS trigger AS $$
				BEGIN RAISE EXCEPTION 'テストで失敗させる'; END; $$ LANGUAGE plpgsql
				""");
		this.jdbc.execute(
				"CREATE TRIGGER fail_review_photos BEFORE INSERT ON review_photos FOR EACH ROW EXECUTE FUNCTION fail_review_photos()");

		assertThat(create(List.of(photo("a.png", png(1)))).getResponse().getStatus()).isEqualTo(500);
		assertNothingLeft();
	}

	// TC-04-6（BR-5）
	@Test
	void S3から消せなかった写真は記録して1日1回消し直す() {
		long id = PhotoApiTests.id(create(List.of(photo("a.png", png(1)))));
		String key = this.jdbc.queryForObject("SELECT storage_key FROM review_photos", String.class);
		doThrow(SdkClientException.create("S3 につながらない")).when(this.storage).delete(anyString());

		// S3 から消せなくても、レビューの削除は成功する
		assertThat(this.mvc.delete().uri("/api/reviews/{id}", id).with(loggedIn(this.taro)).with(csrf()))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(this.jdbc.queryForList("SELECT storage_key FROM storage_deletions", String.class))
			.containsExactly(key);
		assertThat(bucketKeys()).containsExactly(key);

		// 次の日の消し直しでは S3 が戻っている
		doCallRealMethod().when(this.storage).delete(anyString());
		this.deleter.retry();
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM storage_deletions", Integer.class)).isZero();
		assertThat(bucketKeys()).isEmpty();
	}

	private MvcTestResult create(List<MockMultipartFile> files) {
		MockMultipartHttpServletRequestBuilder request = MockMvcRequestBuilders.multipart("/api/books/{bookId}/reviews",
				this.book);
		files.forEach(request::file);
		return this.mvc
			.perform(request.param("rating", "4").param("body", "感想").with(loggedIn(this.taro)).with(csrf()));
	}

	private void assertNothingLeft() {
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM reviews", Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM review_photos", Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM reading_statuses", Integer.class)).isZero();
		assertThat(bucketKeys()).isEmpty();
	}

	private List<String> bucketKeys() {
		return this.s3.listObjectsV2((request) -> request.bucket(TestcontainersConfiguration.BUCKET))
			.contents()
			.stream()
			.map(S3Object::key)
			.toList();
	}

}

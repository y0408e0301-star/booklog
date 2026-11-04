package com.example.booklog.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

// S3 に写真を置く・消す・署名付き URL を作る（FN-04 5 章）。S3 とは通信せずに確かめる
@ExtendWith(MockitoExtension.class)
class PhotoStorageTests {

	@Mock
	S3Client s3;

	// 署名付き URL は手元で計算するだけなので、本物の部品を使う
	private S3Presigner presigner;

	private PhotoStorage storage;

	@BeforeEach
	void setUp() {
		this.presigner = S3Presigner.builder()
			.region(Region.AP_NORTHEAST_1)
			.endpointOverride(URI.create("http://localhost:9000"))
			.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "secret")))
			.build();
		this.storage = new PhotoStorage(this.s3, this.presigner,
				new StorageProperties("bl-photos-test", "ap-northeast-1", null, null, null));
	}

	@AfterEach
	void tearDown() {
		this.presigner.close();
	}

	@Test
	void キーはフォルダとUUIDと拡張子で元のファイル名を使わない() {
		assertThat(PhotoStorage.newKey("reviews", PhotoFormat.PNG))
			.matches("reviews/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png");
	}

	@Test
	void 毎回違うキーを作る() {
		assertThat(PhotoStorage.newKey("reviews", PhotoFormat.PNG))
			.isNotEqualTo(PhotoStorage.newKey("reviews", PhotoFormat.PNG));
	}

	@Test
	void ContentTypeを付けてバケットに置く() {
		this.storage.put("reviews/a.webp", PhotoFormat.WEBP, new byte[] { 1, 2, 3 });
		ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
		verify(this.s3).putObject(request.capture(), any(RequestBody.class));
		assertThat(request.getValue().bucket()).isEqualTo("bl-photos-test");
		assertThat(request.getValue().key()).isEqualTo("reviews/a.webp");
		assertThat(request.getValue().contentType()).isEqualTo("image/webp");
	}

	@Test
	void バケットのキーを消す() {
		this.storage.delete("reviews/a.png");
		verify(this.s3)
			.deleteObject(DeleteObjectRequest.builder().bucket("bl-photos-test").key("reviews/a.png").build());
	}

	// N-16
	@Test
	void 署名付きURLは15分で切れる() {
		PhotoStorage.SignedUrl url = this.storage.signedUrl("reviews/a.png");
		assertThat(url.url()).startsWith("http://localhost:9000/bl-photos-test/reviews/a.png?")
			.contains("X-Amz-Expires=900")
			.contains("X-Amz-Signature=");
		assertThat(url.expiresAt()).isCloseTo(Instant.now().plus(PhotoStorage.URL_TTL), within(1, ChronoUnit.MINUTES));
	}

}

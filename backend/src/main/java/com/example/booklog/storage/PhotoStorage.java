package com.example.booklog.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

// S3 に写真を置く・消す・署名付き URL を作る（機能定義書 FN-04 5 章）。
// S3 との通信に失敗したときは、AWS SDK の例外（SdkException）がそのまま出る
@Component
public class PhotoStorage {

	// 署名付き URL の有効期限（N-16）
	static final Duration URL_TTL = Duration.ofMinutes(15);

	private final S3Client s3;

	private final S3Presigner presigner;

	private final String bucket;

	PhotoStorage(S3Client s3, S3Presigner presigner, StorageProperties properties) {
		this.s3 = s3;
		this.presigner = presigner;
		this.bucket = properties.bucket();
	}

	// キーは「{folder}/{UUID}.{拡張子}」。元のファイル名は使わない（5 章の 1）
	public static String newKey(String folder, PhotoFormat format) {
		return folder + "/" + UUID.randomUUID() + "." + format.extension();
	}

	// Content-Type を付けて置く（5 章の 2）。ブラウザが画像として表示するため
	public void put(String key, PhotoFormat format, byte[] data) {
		this.s3.putObject(
				PutObjectRequest.builder().bucket(this.bucket).key(key).contentType(format.contentType()).build(),
				RequestBody.fromBytes(data));
	}

	public void delete(String key) {
		this.s3.deleteObject(DeleteObjectRequest.builder().bucket(this.bucket).key(key).build());
	}

	// URL は手元で計算するので、S3 とは通信しない（5 章の 4）
	public SignedUrl signedUrl(String key) {
		PresignedGetObjectRequest presigned = this.presigner.presignGetObject(GetObjectPresignRequest.builder()
			.signatureDuration(URL_TTL)
			.getObjectRequest(GetObjectRequest.builder().bucket(this.bucket).key(key).build())
			.build());
		return new SignedUrl(presigned.url().toString(), presigned.expiration());
	}

	public record SignedUrl(String url, Instant expiresAt) {
	}

}

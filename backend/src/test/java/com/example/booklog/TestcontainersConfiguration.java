package com.example.booklog;

import java.net.URI;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.command.InspectContainerResponse;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

// テスト用の PostgreSQL と S3（MinIO）を Docker で起動し、接続先を自動で差し替える。
// 開発用の DB（compose.yaml）と S3 の開発用バケットは使わないので、AWS の鍵がなくてもテストできる（Docker は動かしておく）。
// テストクラスどうしで Spring のコンテキストを使い回すので、コンテナは 1 回だけ起動する
@TestConfiguration(proxyBeanMethods = false)
@Import(DatabaseTestConfiguration.class)
public class TestcontainersConfiguration {

	public static final String BUCKET = "bl-photos-test";

	// MinIO の公式イメージ（minio/minio）は配られなくなったので、Chainguard が作り直したものを使う。
	// 無料で取れるのは latest タグだけなので、ダイジェストで版を固定する（中身は MinIO RELEASE.2026-09-22T19-25-18Z）
	private static final DockerImageName MINIO = DockerImageName
		.parse("chainguard/minio@sha256:709099319add48dfb44aa596a7070d3ddd8dd04d7ae881765719aec29df9e510")
		.asCompatibleSubstituteFor("minio/minio");

	// 起動したらテスト用のバケットを作る
	@Bean
	MinIOContainer minioContainer() {
		return new MinIOContainer(MINIO) {
			@Override
			protected void containerIsStarted(InspectContainerResponse containerInfo) {
				super.containerIsStarted(containerInfo);
				try (S3Client s3 = s3Client(this)) {
					s3.createBucket((request) -> request.bucket(BUCKET));
				}
			}
		};
	}

	@Bean
	DynamicPropertyRegistrar storageProperties(MinIOContainer minio) {
		return (registry) -> {
			registry.add("app.storage.endpoint", minio::getS3URL);
			registry.add("app.storage.access-key", minio::getUserName);
			registry.add("app.storage.secret-key", minio::getPassword);
			registry.add("app.storage.bucket", () -> BUCKET);
		};
	}

	public static S3Client s3Client(MinIOContainer minio) {
		return S3Client.builder()
			.endpointOverride(URI.create(minio.getS3URL()))
			.forcePathStyle(true)
			.region(Region.AP_NORTHEAST_1)
			.credentialsProvider(StaticCredentialsProvider
				.create(AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
			.build();
	}

}

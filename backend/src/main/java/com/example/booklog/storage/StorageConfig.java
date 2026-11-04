package com.example.booklog.storage;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

// S3 につなぐための部品（AWS SDK for Java 2.x）。接続先は StorageProperties で変える（N-27）
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class StorageConfig {

	@Bean(destroyMethod = "close")
	S3Client s3Client(StorageProperties properties) {
		var builder = S3Client.builder()
			.region(Region.of(properties.region()))
			.credentialsProvider(credentials(properties));
		if (properties.hasEndpoint()) {
			// MinIO は「エンドポイント/バケット/キー」の形の URL で受ける
			builder.endpointOverride(URI.create(properties.endpoint())).forcePathStyle(true);
		}
		return builder.build();
	}

	@Bean(destroyMethod = "close")
	S3Presigner s3Presigner(StorageProperties properties) {
		var builder = S3Presigner.builder()
			.region(Region.of(properties.region()))
			.credentialsProvider(credentials(properties));
		if (properties.hasEndpoint()) {
			builder.endpointOverride(URI.create(properties.endpoint()))
				.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
		}
		return builder.build();
	}

	private static AwsCredentialsProvider credentials(StorageProperties properties) {
		if (properties.hasStaticKeys()) {
			return StaticCredentialsProvider
				.create(AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
		}
		return DefaultCredentialsProvider.builder().build();
	}

}

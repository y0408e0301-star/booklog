package com.example.booklog.storage;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// S3 から消せなかったファイル（データ設計書 4.10、BR-5）。StorageDeleter が 1 日 1 回消し直す
@Entity
@Table(name = "storage_deletions")
public class StorageDeletion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private @Nullable Long id;

	@Column(nullable = false, updatable = false)
	private String storageKey;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected StorageDeletion() {
		this("", Instant.EPOCH);
	}

	StorageDeletion(String storageKey, Instant now) {
		this.storageKey = storageKey;
		this.createdAt = now;
	}

	public String getStorageKey() {
		return this.storageKey;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

}

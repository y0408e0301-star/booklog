package com.example.booklog.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.booklog.TestEntities;

import software.amazon.awssdk.core.exception.SdkClientException;

// S3 のファイルを消す。消せなかったものは記録して後で消し直す（データ設計書 BR-5）
@ExtendWith(MockitoExtension.class)
class StorageDeleterTests {

	@Mock
	PhotoStorage storage;

	@Mock
	StorageDeletionRepository deletions;

	private StorageDeleter deleter;

	@BeforeEach
	void setUp() {
		this.deleter = new StorageDeleter(this.storage, this.deletions, TestEntities.fixedClock());
	}

	@Test
	void すべてのキーを消す() {
		this.deleter.deleteOrRecord(List.of("reviews/a.png", "reviews/b.png"));
		verify(this.storage).delete("reviews/a.png");
		verify(this.storage).delete("reviews/b.png");
		verify(this.deletions, never()).save(any());
	}

	// BR-5
	// TC-04-6
	@Test
	void 消せなかったキーを記録しほかのキーは続けて消す() {
		doThrow(SdkClientException.create("S3 につながらない")).when(this.storage).delete("reviews/a.png");
		doNothing().when(this.storage).delete("reviews/b.png");
		this.deleter.deleteOrRecord(List.of("reviews/a.png", "reviews/b.png"));

		ArgumentCaptor<StorageDeletion> saved = ArgumentCaptor.forClass(StorageDeletion.class);
		verify(this.deletions).save(saved.capture());
		assertThat(saved.getValue().getStorageKey()).isEqualTo("reviews/a.png");
		assertThat(saved.getValue().getCreatedAt()).isEqualTo(TestEntities.NOW);
		verify(this.storage).delete("reviews/b.png");
	}

	@Test
	void すでに記録してあるキーは重ねて記録しない() {
		doThrow(SdkClientException.create("S3 につながらない")).when(this.storage).delete("reviews/a.png");
		when(this.deletions.existsByStorageKey("reviews/a.png")).thenReturn(true);
		this.deleter.deleteOrRecord(List.of("reviews/a.png"));
		verify(this.deletions, never()).save(any());
	}

	// BR-5
	// TC-04-6
	@Test
	void 消し直しで消せたものは記録も消し消せなかったものは残す() {
		StorageDeletion ok = new StorageDeletion("reviews/a.png", TestEntities.NOW);
		StorageDeletion ng = new StorageDeletion("reviews/b.png", TestEntities.NOW);
		when(this.deletions.findAll()).thenReturn(List.of(ok, ng));
		doNothing().when(this.storage).delete("reviews/a.png");
		doThrow(SdkClientException.create("S3 につながらない")).when(this.storage).delete("reviews/b.png");

		this.deleter.retry();

		verify(this.deletions).delete(ok);
		verify(this.deletions, never()).delete(ng);
	}

}

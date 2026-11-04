package com.example.booklog.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

// 写真の形式はファイルの先頭のバイトで決める（FN-04 4 章）
class PhotoFormatTests {

	@Test
	void JPEG() {
		assertThat(PhotoFormat.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0))).contains(PhotoFormat.JPEG);
	}

	@Test
	void PNG() {
		assertThat(PhotoFormat.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0))).contains(PhotoFormat.PNG);
	}

	// GIF は使えない（PH-1）
	@Test
	void GIFは使えない() {
		assertThat(PhotoFormat.detect(ascii("GIF87a"))).isEmpty();
		assertThat(PhotoFormat.detect(ascii("GIF89a"))).isEmpty();
	}

	@Test
	void WebPは先頭のRIFFと9バイト目からのWEBP() {
		assertThat(PhotoFormat.detect(ascii("RIFF\0\0\0\0WEBPVP8 "))).contains(PhotoFormat.WEBP);
	}

	@Test
	void RIFFでもWEBPでなければ使えない() {
		// WAV の音声ファイル
		assertThat(PhotoFormat.detect(ascii("RIFF\0\0\0\0WAVEfmt "))).isEmpty();
	}

	@Test
	void 先頭のバイトが足りなければ使えない() {
		assertThat(PhotoFormat.detect(new byte[0])).isEmpty();
		assertThat(PhotoFormat.detect(bytes(0xFF, 0xD8))).isEmpty();
		assertThat(PhotoFormat.detect(ascii("RIFF\0\0\0\0WEB"))).isEmpty();
	}

	@Test
	void 写真でないファイルは使えない() {
		assertThat(PhotoFormat.detect(ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"/>"))).isEmpty();
		assertThat(PhotoFormat.detect(ascii("%PDF-1.7"))).isEmpty();
	}

	@Test
	void ContentTypeと拡張子() {
		assertThat(PhotoFormat.JPEG.contentType()).isEqualTo("image/jpeg");
		assertThat(PhotoFormat.JPEG.extension()).isEqualTo("jpg");
		assertThat(PhotoFormat.WEBP.contentType()).isEqualTo("image/webp");
		assertThat(PhotoFormat.WEBP.extension()).isEqualTo("webp");
	}

	private static byte[] bytes(int... values) {
		byte[] data = new byte[values.length];
		for (int i = 0; i < values.length; i++) {
			data[i] = (byte) values[i];
		}
		return data;
	}

	private static byte[] ascii(String text) {
		return text.getBytes(StandardCharsets.ISO_8859_1);
	}

}

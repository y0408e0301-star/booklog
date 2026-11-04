package com.example.booklog.storage;

import java.util.Arrays;
import java.util.Optional;

// 添えられる写真の形式（機能定義書 FN-04 4 章）。拡張子ではなく、ファイルの先頭のバイト（マジックナンバー）で決める。GIF は使えない
public enum PhotoFormat {

	JPEG("image/jpeg", "jpg"),

	PNG("image/png", "png"),

	WEBP("image/webp", "webp");

	private static final byte[] JPEG_MAGIC = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

	private static final byte[] PNG_MAGIC = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };

	private static final byte[] RIFF = { 'R', 'I', 'F', 'F' };

	private static final byte[] WEBP_MAGIC = { 'W', 'E', 'B', 'P' };

	private final String contentType;

	private final String extension;

	PhotoFormat(String contentType, String extension) {
		this.contentType = contentType;
		this.extension = extension;
	}

	public String contentType() {
		return this.contentType;
	}

	public String extension() {
		return this.extension;
	}

	// WebP は先頭が "RIFF"、9〜12 バイト目が "WEBP"
	public static Optional<PhotoFormat> detect(byte[] data) {
		if (startsWith(data, 0, JPEG_MAGIC)) {
			return Optional.of(JPEG);
		}
		if (startsWith(data, 0, PNG_MAGIC)) {
			return Optional.of(PNG);
		}
		if (startsWith(data, 0, RIFF) && startsWith(data, 8, WEBP_MAGIC)) {
			return Optional.of(WEBP);
		}
		return Optional.empty();
	}

	private static boolean startsWith(byte[] data, int offset, byte[] magic) {
		return data.length >= offset + magic.length
				&& Arrays.equals(data, offset, offset + magic.length, magic, 0, magic.length);
	}

}

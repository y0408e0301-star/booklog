package com.example.booklog.shelf;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

// ステータスごとの冊数（FN-11 6 章の bookshelfCounts）。JSON の名前は読書ステータスの名前のまま。
// 名前を変えているので、仕様書の必須は自動では付かない（OpenApiConfig）。@Schema で書く
public record BookshelfCounts(@JsonProperty("WANT") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long want,
		@JsonProperty("READING") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long reading,
		@JsonProperty("READ") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long read) {
}

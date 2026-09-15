package com.mlbb.highlight.storage

data class HighlightEntity(
    val id: Long = 0L,
    val filePath: String,
    val createdAtMs: Long,
    val title: String,
    val durationMs: Long = 0L
)

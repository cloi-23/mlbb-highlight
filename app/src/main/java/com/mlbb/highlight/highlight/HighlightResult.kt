package com.mlbb.highlight.highlight

import java.io.File

data class HighlightResult(
    val outputFile: File,
    val triggerTimeMs: Long,
    val success: Boolean = true,
    val message: String = "Highlight created"
)

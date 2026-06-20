package ai.torad.aisdk

import kotlin.math.sqrt

public object EmbeddingMath {

    public fun cosineSimilarity(left: List<Float>, right: List<Float>): Float {
        require(left.size == right.size) { "Embedding vectors must have the same dimension" }
        require(left.isNotEmpty()) { "Embedding vectors must not be empty" }
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            val a = left[index].toDouble()
            val b = right[index].toDouble()
            dot += a * b
            leftNorm += a * a
            rightNorm += b * b
        }
        val denom = sqrt(leftNorm) * sqrt(rightNorm)
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }
}

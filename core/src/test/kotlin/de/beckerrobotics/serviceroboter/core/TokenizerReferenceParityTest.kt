package de.beckerrobotics.serviceroboter.core

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals

class TokenizerReferenceParityTest {
    @Test fun bothTokenizersMatchHuggingFaceOnGermanAndUnicodeFixtures() {
        val root = File(System.getProperty("robot.projectRoot"))
        val assets = File(root, "app/src/main/assets")
        val jinaVocab = javaClass.getResourceAsStream("/jina-vocab.tsv")!!.bufferedReader().useLines { lines ->
            lines.associate { line -> val parts = line.split('\t')
                String(Base64.getDecoder().decode(parts[0]), Charsets.UTF_8) to parts[1].toLong()
            }
        }
        val merges = File(assets, "embeddings/merges.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split(' ').let { p -> p[0] to p[1] } }
        val bpe = ByteBpeTokenizer(jinaVocab, merges)
        val wordpiece = WordPieceTokenizer(File(assets, "vocab.txt").readLines().withIndex()
            .associate { it.value to it.index.toLong() })
        javaClass.getResourceAsStream("/tokenizer-parity.tsv")!!.bufferedReader().useLines { lines ->
            lines.forEachIndexed { index, line ->
                val parts = line.split('\t')
                val text = String(Base64.getDecoder().decode(parts[1]), Charsets.UTF_8)
                val expected = parts[2].split(',').map { it.toLong() }.toLongArray()
                val actual = if (parts[0] == "bpe") bpe.encode(text) else wordpiece.encode(text)
                assertContentEquals(expected, actual, "Reference case $index (${parts[0]}): ${text.take(100)}")
            }
        }
    }
}

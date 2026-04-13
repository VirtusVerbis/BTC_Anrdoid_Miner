package com.btcminer.android.util

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import com.btcminer.android.R
import com.google.android.material.color.MaterialColors
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Pretty-prints last Stratum wire lines for dashboard pages 4–5 and builds optional index legends.
 */
object StratumJsonUiFormatter {

    /**
     * Pretty-print with 2-space indent when [raw] is valid JSON object or array; otherwise return trimmed raw.
     */
    fun prettyStratumJsonOrRaw(raw: String): CharSequence {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return try {
            JSONObject(t).toString(2)
        } catch (_: Exception) {
            try {
                JSONArray(t).toString(2)
            } catch (_: Exception) {
                t
            }
        }
    }

    /**
     * Pretty-print like [prettyStratumJsonOrRaw] with syntax colors aligned to the dashboard reference style:
     * lavender key names ([R.color.json_syntax_key]) with quotes in punctuation color ([R.color.json_syntax_punctuation]),
     * pink/magenta string values (including quotes), orange numbers ([R.color.bitcoin_orange]),
     * teal/cyan for null/true/false ([R.color.json_syntax_keyword]), and light structural punctuation
     * (braces, brackets, colons, commas, indent). Non-JSON [raw] returns trimmed text without spans.
     */
    fun prettyStratumJsonSpanned(context: Context, raw: String): CharSequence {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val palette = jsonSyntaxPalette(context)
        return try {
            val ssb = SpannableStringBuilder()
            appendJsonObject(ssb, JSONObject(t), 0, palette)
            ssb
        } catch (_: Exception) {
            try {
                val ssb = SpannableStringBuilder()
                appendJsonArray(ssb, JSONArray(t), 0, palette)
                ssb
            } catch (_: Exception) {
                t
            }
        }
    }

    private data class JsonSyntaxPalette(
        val key: Int,
        val string: Int,
        val number: Int,
        val keyword: Int,
        val punctuation: Int,
    )

    private fun jsonSyntaxPalette(context: Context) = JsonSyntaxPalette(
        key = ContextCompat.getColor(context, R.color.json_syntax_key),
        string = ContextCompat.getColor(context, R.color.json_syntax_string),
        number = ContextCompat.getColor(context, R.color.bitcoin_orange),
        keyword = ContextCompat.getColor(context, R.color.json_syntax_keyword),
        punctuation = ContextCompat.getColor(context, R.color.json_syntax_punctuation),
    )

    private fun appendSpanned(ssb: SpannableStringBuilder, text: String, color: Int) {
        val start = ssb.length
        ssb.append(text)
        ssb.setSpan(ForegroundColorSpan(color), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun jsonEscaped(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append(String.format(Locale.US, "\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    private fun appendJsonObject(
        ssb: SpannableStringBuilder,
        obj: JSONObject,
        indent: Int,
        p: JsonSyntaxPalette,
    ) {
        val keys = obj.keys().asSequence().toList()
        if (keys.isEmpty()) {
            appendSpanned(ssb, "{}", p.punctuation)
            return
        }
        appendSpanned(ssb, "{\n", p.punctuation)
        keys.forEachIndexed { idx, key ->
            repeat(indent + 1) { appendSpanned(ssb, "  ", p.punctuation) }
            appendSpanned(ssb, "\"", p.punctuation)
            appendSpanned(ssb, jsonEscaped(key), p.key)
            appendSpanned(ssb, "\"", p.punctuation)
            appendSpanned(ssb, ": ", p.punctuation)
            appendJsonValue(ssb, obj.get(key), indent + 1, p)
            if (idx < keys.lastIndex) appendSpanned(ssb, ",\n", p.punctuation) else appendSpanned(ssb, "\n", p.punctuation)
        }
        repeat(indent) { appendSpanned(ssb, "  ", p.punctuation) }
        appendSpanned(ssb, "}", p.punctuation)
    }

    private fun appendJsonArray(
        ssb: SpannableStringBuilder,
        arr: JSONArray,
        indent: Int,
        p: JsonSyntaxPalette,
    ) {
        val n = arr.length()
        if (n == 0) {
            appendSpanned(ssb, "[]", p.punctuation)
            return
        }
        appendSpanned(ssb, "[\n", p.punctuation)
        for (i in 0 until n) {
            repeat(indent + 1) { appendSpanned(ssb, "  ", p.punctuation) }
            appendJsonValue(ssb, arr.get(i), indent + 1, p)
            if (i < n - 1) appendSpanned(ssb, ",\n", p.punctuation) else appendSpanned(ssb, "\n", p.punctuation)
        }
        repeat(indent) { appendSpanned(ssb, "  ", p.punctuation) }
        appendSpanned(ssb, "]", p.punctuation)
    }

    @Suppress("DEPRECATION")
    private fun appendJsonValue(
        ssb: SpannableStringBuilder,
        value: Any?,
        indent: Int,
        p: JsonSyntaxPalette,
    ) {
        when {
            value == null || value === JSONObject.NULL -> appendSpanned(ssb, "null", p.keyword)
            value is JSONObject -> appendJsonObject(ssb, value, indent, p)
            value is JSONArray -> appendJsonArray(ssb, value, indent, p)
            value is Boolean -> appendSpanned(ssb, if (value) "true" else "false", p.keyword)
            value is Number -> appendSpanned(ssb, JSONObject.numberToString(value), p.number)
            value is String -> {
                appendSpanned(ssb, "\"", p.string)
                appendSpanned(ssb, jsonEscaped(value), p.string)
                appendSpanned(ssb, "\"", p.string)
            }
            else -> appendSpanned(ssb, JSONObject.quote(value.toString()), p.string)
        }
    }

    /**
     * Index legend for the last raw line, or null to hide the footer.
     * Requires a non-empty JSON-RPC [params] array; null/empty [params] yields no footer (e.g. mining.extranonce.subscribe).
     * [isInbound] true = pool → app, false = app → pool.
     */
    fun indicesFooter(context: Context, raw: String, isInbound: Boolean): CharSequence? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val obj = try {
            JSONObject(t)
        } catch (_: Exception) {
            return null
        }
        val method = obj.optString("method", "")
        if (method.isEmpty()) return null
        val params = obj.optJSONArray("params")
        if (params == null || params.length() == 0) return null

        val orange = ContextCompat.getColor(context, R.color.bitcoin_orange)
        val onSurface = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurface,
            Color.parseColor("#E0E0E0"),
        )
        return if (isInbound) {
            when (method) {
                "mining.notify" -> buildNotifyIndicesFooter(orange, onSurface)
                "mining.set_difficulty" -> buildSetDifficultyIndicesFooter(orange, onSurface)
                "mining.set_extranonce" -> buildSetExtranonceIndicesFooter(orange, onSurface)
                "client.reconnect" -> buildClientReconnectIndicesFooter(orange, onSurface)
                else -> null
            }
        } else {
            when (method) {
                "mining.submit" -> buildSubmitIndicesFooter(orange, onSurface)
                "mining.authorize" -> buildAuthorizeIndicesFooter(orange, onSurface)
                "mining.subscribe" -> buildSubscribeIndicesFooter(orange, onSurface)
                else -> null
            }
        }
    }

    private fun appendIndexPart(
        ssb: SpannableStringBuilder,
        index: String,
        tail: String,
        orange: Int,
        onSurface: Int,
    ) {
        val start = ssb.length
        ssb.append(index)
        ssb.setSpan(ForegroundColorSpan(orange), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val textStart = ssb.length
        ssb.append(tail)
        ssb.setSpan(ForegroundColorSpan(onSurface), textStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun buildNotifyIndicesFooter(orange: Int, onSurface: Int): CharSequence {
        val ssb = SpannableStringBuilder()
        val introStart = ssb.length
        ssb.append("Indices: ")
        ssb.setSpan(ForegroundColorSpan(onSurface), introStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        appendIndexPart(ssb, "0", " job id, ", orange, onSurface)
        appendIndexPart(ssb, "1", " prevhash, ", orange, onSurface)
        appendIndexPart(ssb, "2", " coinb1, ", orange, onSurface)
        appendIndexPart(ssb, "3", " coinb2, ", orange, onSurface)
        appendIndexPart(ssb, "4", " merkle branches array, ", orange, onSurface)
        appendIndexPart(ssb, "5", " version, ", orange, onSurface)
        appendIndexPart(ssb, "6", " nbits, ", orange, onSurface)
        appendIndexPart(ssb, "7", " ntime, ", orange, onSurface)
        appendIndexPart(ssb, "8", " clean_jobs.", orange, onSurface)
        return ssb
    }

    private fun buildSubmitIndicesFooter(orange: Int, onSurface: Int): CharSequence {
        val ssb = SpannableStringBuilder()
        val introStart = ssb.length
        ssb.append("Indices: ")
        ssb.setSpan(ForegroundColorSpan(onSurface), introStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        appendIndexPart(ssb, "0", " username, ", orange, onSurface)
        appendIndexPart(ssb, "1", " job id, ", orange, onSurface)
        appendIndexPart(ssb, "2", " extranonce2 (hex), ", orange, onSurface)
        appendIndexPart(ssb, "3", " ntime (hex), ", orange, onSurface)
        appendIndexPart(ssb, "4", " nonce (hex).", orange, onSurface)
        return ssb
    }

    private fun buildAuthorizeIndicesFooter(orange: Int, onSurface: Int): CharSequence {
        val ssb = SpannableStringBuilder()
        val introStart = ssb.length
        ssb.append("Indices: ")
        ssb.setSpan(ForegroundColorSpan(onSurface), introStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        appendIndexPart(ssb, "0", " username, ", orange, onSurface)
        appendIndexPart(ssb, "1", " password.", orange, onSurface)
        return ssb
    }

    private fun buildSubscribeIndicesFooter(orange: Int, onSurface: Int): CharSequence {
        val ssb = SpannableStringBuilder()
        val introStart = ssb.length
        ssb.append("Indices: ")
        ssb.setSpan(ForegroundColorSpan(onSurface), introStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        appendIndexPart(ssb, "0", " client subscription name / user agent.", orange, onSurface)
        return ssb
    }

    private fun buildSetDifficultyIndicesFooter(orange: Int, onSurface: Int): CharSequence {
        val ssb = SpannableStringBuilder()
        val introStart = ssb.length
        ssb.append("Indices: ")
        ssb.setSpan(ForegroundColorSpan(onSurface), introStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        appendIndexPart(ssb, "0", " difficulty.", orange, onSurface)
        return ssb
    }

    private fun buildSetExtranonceIndicesFooter(orange: Int, onSurface: Int): CharSequence {
        val ssb = SpannableStringBuilder()
        val introStart = ssb.length
        ssb.append("Indices: ")
        ssb.setSpan(ForegroundColorSpan(onSurface), introStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        appendIndexPart(ssb, "0", " extranonce1 (hex), ", orange, onSurface)
        appendIndexPart(ssb, "1", " extranonce2 size (optional).", orange, onSurface)
        return ssb
    }

    private fun buildClientReconnectIndicesFooter(orange: Int, onSurface: Int): CharSequence {
        val ssb = SpannableStringBuilder()
        val introStart = ssb.length
        ssb.append("Indices: ")
        ssb.setSpan(ForegroundColorSpan(onSurface), introStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        appendIndexPart(ssb, "0", " host, ", orange, onSurface)
        appendIndexPart(ssb, "1", " port.", orange, onSurface)
        return ssb
    }
}

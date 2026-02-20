package com.withesse.beacon.log

import java.util.concurrent.ConcurrentHashMap

/**
 * Log sensitive data masking.
 * Auto-detect: phone / ID card / email / bank card / JSON field values
 *
 * 日志敏感信息脱敏。
 * 自动识别: 手机号 / 身份证 / 邮箱 / 银行卡 / JSON 字段值
 */
object SensitiveFilter {

    private val PHONE    = Regex("""\b1[3-9]\d{9}\b""")
    private val ID_CARD  = Regex("""\b\d{17}[\dXx]\b""")
    private val EMAIL    = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")
    private val BANK     = Regex("""\b\d{16,19}\b""")

    private val jsonPatternCache = ConcurrentHashMap<String, Regex>()
    private val kvPatternCache = ConcurrentHashMap<String, Regex>()

    fun filter(msg: String, keys: List<String>): String {
        var result = msg

        if (keys.any { it.equals("phone", true) }) {
            result = PHONE.replace(result) { mask(it.value) }
        }
        if (keys.any { it.equals("idCard", true) }) {
            result = ID_CARD.replace(result) { mask(it.value) }
        }
        if (keys.any { it.equals("email", true) }) {
            result = EMAIL.replace(result) { mask(it.value) }
        }
        if (keys.any { it.equals("bankCard", true) }) {
            result = BANK.replace(result) { mask(it.value) }
        }

        keys.forEach { key ->
            // JSON pattern: "key":"value" | JSON 模式: "key":"value"
            val jsonPattern = jsonPatternCache.getOrPut(key.lowercase()) {
                Regex(""""($key)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            }
            result = jsonPattern.replace(result) { """"${it.groupValues[1]}":"***"""" }

            // Plain text: key=value (URL query params, log key-value pairs) | 纯文本: key=value（URL 参数、日志键值对）
            val kvPattern = kvPatternCache.getOrPut(key.lowercase()) {
                Regex("""(^|[?&\s])($key)=([^&\s]+)""", RegexOption.IGNORE_CASE)
            }
            result = kvPattern.replace(result) { "${it.groupValues[1]}${it.groupValues[2]}=***" }
        }

        return result
    }

    private fun mask(v: String): String {
        if (v.length <= 4) return "****"
        return v.take(2) + "*".repeat(v.length - 4) + v.takeLast(2)
    }
}

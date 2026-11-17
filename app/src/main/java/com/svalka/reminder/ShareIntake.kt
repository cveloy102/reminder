package com.svalka.reminder

private val URL_IN_TEXT = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

/** First web address inside shared text, without trailing punctuation. */
fun findUrlInText(text: String): String? =
    URL_IN_TEXT.find(text)?.value?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
        ?.takeIf { it.length > "https://".length }

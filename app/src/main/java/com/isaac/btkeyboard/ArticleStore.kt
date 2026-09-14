package com.isaac.btkeyboard

import android.content.Context
import java.text.Normalizer

object ArticleStore {
    private const val PREFS = "bt_keyboard_prefs"
    private const val KEY_ARTICLES = "articles_text"

    fun normalize(input: String): String {
        val temp = Normalizer.normalize(input.lowercase(), Normalizer.Form.NFD)
        return temp.replace(Regex("\\p{Mn}+"), "").trim()
    }

    private fun getRawText(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ARTICLES, "tomate=24\nlechuga=32") ?: ""
    }

    private fun saveRawText(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ARTICLES, text).apply()
    }

    fun getAll(context: Context): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        getRawText(context).lines().forEach { line ->
            val parts = line.split("=")
            if (parts.size == 2) {
                val name = normalize(parts[0].trim())
                val code = parts[1].trim()
                if (name.isNotEmpty() && code.isNotEmpty()) map[name] = code
            }
        }
        return map
    }

    fun addOrUpdate(context: Context, name: String, code: String) {
        val map = getAll(context).toMutableMap()
        map[normalize(name)] = code
        val text = map.entries.joinToString("\n") { "${it.key}=${it.value}" }
        saveRawText(context, text)
    }

    fun delete(context: Context, name: String) {
        val map = getAll(context).toMutableMap()
        map.remove(normalize(name))
        val text = map.entries.joinToString("\n") { "${it.key}=${it.value}" }
        saveRawText(context, text)
    }
}

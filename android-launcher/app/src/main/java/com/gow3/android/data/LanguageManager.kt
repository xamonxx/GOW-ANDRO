package com.gow3.android.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 启动器语言设置：Bahasa Indonesia（默认）/ 跟随系统 / 简体中文 / English。
 *
 * 读 `SharedPreferences`（`gow3` key `language`）得到覆盖值（`in`/`system`/`zh`/`en`），
 * 再解析出应注入 Activity 的 `Locale`。未设置时为印尼语；`system` 表示跟随设备系统语言。
 * `MainActivity` 与 `GameActivity` 的 `attachBaseContext` 都经 [wrap] 应用它，
 * About 页语言入口负责写入。
 */
object LanguageManager {
    const val PREF_NAME = "gow3"
    const val KEY_LANGUAGE = "language"
    const val FOLLOW_SYSTEM = "system"
    const val ID = "in"
    const val ZH = "zh"
    const val EN = "en"

    /** 读取持久化的语言覆盖值；未设置时返回 [ID]（印尼语为默认）。 */
    @JvmStatic
    fun read(context: Context): String =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, ID) ?: ID

    /** 持久化语言覆盖值。 */
    @JvmStatic
    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, value).apply()
    }

    /** 解析当前生效的 Locale：`system` 跟随设备系统语言，其余为固定覆盖（`in` 对应 res/values-in）。 */
    @JvmStatic
    fun resolve(context: Context): Locale = when (read(context)) {
        ZH -> Locale("zh", "CN")
        EN -> Locale.ENGLISH
        FOLLOW_SYSTEM -> Locale.getDefault()
        else -> Locale("in", "ID")
    }

    /** 返回已注入当前语言 Locale 的 Context，供 Activity.attachBaseContext 使用。 */
    @JvmStatic
    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(resolve(base))
        return base.createConfigurationContext(config)
    }
}

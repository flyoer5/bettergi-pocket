package com.bettergi.pocket.genshin

object GenshinPackages {
    val CANDIDATES: List<String> = listOf(
        "com.miHoYo.Yuanshen",
        "com.miHoYo.ys.bilibili",
        "com.miHoYo.GenshinImpact",
        "com.miHoYo.cloudgames.ys",
        "com.miHoYo.cloudgames.GenshinImpact",
        "com.miHoYo.ys.mi",
    )

    fun isGenshinPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (CANDIDATES.any { it.equals(packageName, ignoreCase = true) }) return true
        val lower = packageName.lowercase()
        return lower.contains("yuanshen") ||
            lower.contains("genshinimpact") ||
            lower.startsWith("com.mihoyo.ys.") ||
            lower.startsWith("com.mihoyo.cloudgames.ys")
    }

    fun pickPreferred(installed: Collection<String>): String? {
        val set = installed.toSet()
        CANDIDATES.firstOrNull { it in set }?.let { return it }
        return installed.firstOrNull { isGenshinPackage(it) }
    }

    fun displayName(packageName: String): String = when (packageName) {
        "com.miHoYo.Yuanshen" -> "国服官服"
        "com.miHoYo.ys.bilibili" -> "Bilibili服"
        "com.miHoYo.GenshinImpact" -> "国际服"
        "com.miHoYo.cloudgames.ys" -> "云原神"
        "com.miHoYo.cloudgames.GenshinImpact" -> "云原神国际服"
        "com.miHoYo.ys.mi" -> "小米渠道服"
        else -> "原神"
    }

    fun shouldAttemptAutoLaunch(
        enabled: Boolean,
        genshinInForeground: Boolean?,
        alreadyAttempted: Boolean,
        allowed: Boolean,
    ): Boolean {
        if (!enabled || !allowed || alreadyAttempted) return false
        // 只有"明确查到自己不在前台"才启动；查询失败(null)时保守跳过，
        // 否则一次 dumpsys 解析失败就会把正在运行的游戏再拉一次（甚至顶掉当前界面）
        return genshinInForeground == false
    }
}

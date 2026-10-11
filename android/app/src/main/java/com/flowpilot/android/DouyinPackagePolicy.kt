package com.flowpilot.android

internal object DouyinPackagePolicy {
    const val MAIN_PACKAGE = "com.ss.android.ugc.aweme"
    const val LITE_PACKAGE = "com.ss.android.ugc.aweme.lite"

    val supportedPackages = listOf(MAIN_PACKAGE, LITE_PACKAGE)

    fun preferredInstalledPackage(isInstalled: (String) -> Boolean): String? =
        supportedPackages.firstOrNull(isInstalled)

    fun isSupportedPackage(packageName: String?): Boolean =
        packageName != null && packageName in supportedPackages
}

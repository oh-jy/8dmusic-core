package j5i.spatial8d

import j5i.spatial8d.ui.AppLanguage

internal fun MainActivity.errorDetail(error: Throwable): String {
    val message = error.message ?: error.javaClass.simpleName
    val origin = error.stackTrace.firstOrNull()?.className.orEmpty()
    return if (AppLanguage.isFirstPartyErrorOrigin(origin) &&
        !(origin.startsWith("j5i.spatial8d.provider.ProviderPluginRunner") && error.cause != null)) {
        AppLanguage.error(this, message)
    } else message
}

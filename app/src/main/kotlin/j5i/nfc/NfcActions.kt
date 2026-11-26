package j5i.spatial8d.nfc

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.tech.Ndef
import android.os.SystemClock
import android.util.Log
import j5i.spatial8d.MainActivity
import j5i.spatial8d.feature.ui.*
import j5i.spatial8d.feature.playback.*
import j5i.spatial8d.feature.source.*
import j5i.spatial8d.audio.Lyrics
import j5i.spatial8d.data.MusicItem
import j5i.spatial8d.data.FAVORITES_INDEX
import j5i.spatial8d.provider.ProviderTrackRef
import j5i.spatial8d.ui.ThemeUi
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "8DNfc"
private val repeatGuard = NfcRepeatGuard()

internal fun MainActivity.isNfcShortcutIntent(intent: Intent?): Boolean {
    val action = intent?.action ?: return false
    return (action == Intent.ACTION_VIEW || action == NfcAdapter.ACTION_NDEF_DISCOVERED) &&
        intent.data?.scheme.equals(NfcShortcut.SCHEME, ignoreCase = true)
}

internal fun MainActivity.handleNfcShortcutIntent(intent: Intent) {
    val shortcut = runCatching { NfcShortcut.parseShortcut(intent.dataString.orEmpty()) }
        .getOrElse {
            Log.w(TAG, "shortcut parse failed: ${it.message}")
            toast(t("nfc.invalid")); return
        }
    if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED &&
        !repeatGuard.accept(shortcut.target, SystemClock.elapsedRealtime())) return
    openNfcShortcut(shortcut)
}

internal fun MainActivity.openNfcShortcut(shortcut: NfcShortcut.Shortcut) {
    val target = shortcut.target
    val list = when (target) {
        is NfcShortcut.Fm -> null
        is NfcShortcut.LocalSong -> target.list
        is NfcShortcut.PluginSong -> target.list
        is NfcShortcut.ProviderSong -> target.list
    }
    if (list == null && runCatching { buildCurrentNfcShortcut() }.getOrNull() == target) {
        if (!isPlaying()) togglePlayback()
        return
    }
    when (target) {
        is NfcShortcut.Fm -> {
            val station = importedStations().firstOrNull { it.id == "${target.providerId}/${target.stationId}" }
            if (station == null) {
                toast(t("playback.source.missing"))
                return
            }
            playRadio(station)
        }
        is NfcShortcut.PluginSong -> {
            val item = findOnlineItem(target)
            if (item != null) playNfcItem(item, list)
            else findLocalByName(shortcut.name)?.let { playNfcItem(it, list) }
                ?: openNfcOnlineSearch(shortcut.name)
        }
        is NfcShortcut.ProviderSong -> {
            val item = findOnlineItem(target)
            if (item != null) playNfcItem(item, list)
            else findLocalByName(shortcut.name)?.let { playNfcItem(it, list) }
                ?: openNfcOnlineSearch(shortcut.name)
        }
        is NfcShortcut.LocalSong -> {
            val byId = songs.firstOrNull { it.id == target.mediaStoreId }
                ?: musicStore.cached().firstOrNull { it.id == target.mediaStoreId }
            val item = byId?.takeIf { shortcut.name == null || sameSongName(it.name, shortcut.name) }
                ?: findLocalByName(shortcut.name)
            if (item != null) playNfcItem(item, list) else openNfcOnlineSearch(shortcut.name)
        }
    }
}

private fun MainActivity.playNfcItem(item: MusicItem, list: String?) {
    if (list != null) restoreNfcList(list, item)
    load(item)
}

private fun MainActivity.findLocalByName(name: String?): MusicItem? {
    if (name.isNullOrBlank()) return null
    return firstLocalSongByName(songs + musicStore.cached(), name)
}

internal fun firstLocalSongByName(items: Iterable<MusicItem>, name: String): MusicItem? {
    val normalized = normalizedSongName(name)
    if (normalized.isEmpty()) return null
    val candidates = items.distinctBy { it.uri }
    return candidates.firstOrNull { sameSongName(it.name, name) }
        ?: candidates.firstOrNull {
            val candidate = normalizedSongName(it.name)
            candidate.isNotEmpty() && (candidate.contains(normalized, ignoreCase = true) ||
                normalized.contains(candidate, ignoreCase = true))
        }
}

private fun sameSongName(first: String, second: String): Boolean =
    normalizedSongName(first).equals(normalizedSongName(second), ignoreCase = true)

private fun normalizedSongName(name: String): String =
    Lyrics.displayTitle(name).trim().replace(Regex("\\s+"), " ")

private fun MainActivity.openNfcOnlineSearch(name: String?) {
    if (name.isNullOrBlank()) {
        toast(t("nfc.list.missing"))
        return
    }
    playlistDialog?.dismiss()
    providerSelection = "@aggregate"
    val query = Lyrics.displayTitle(name)
    if (query.isBlank()) {
        toast(t("nfc.list.missing"))
        return
    }
    onlineQuery = query
    pendingNfcOnlineSearch = query
    providerResults = emptyList()
    pluginResults = emptyList()
    showPlaylist(requestedTab = 2)
}

internal fun MainActivity.showNfcWriteSheet() {
    val target = runCatching { buildCurrentNfcShortcut() }
        .getOrElse {
            Log.w(TAG, "current content has no shortcut target: ${it.message}")
            toast(t(it.message?.takeIf { key -> key.startsWith("nfc.") } ?: "nfc.unsupported")); return
        }
    val uri = runCatching { NfcShortcut.create(target, currentItem?.name) }
        .getOrElse {
            Log.w(TAG, "shortcut uri rejected: ${it.message}")
            toast(t("nfc.unsupported")); return
        }
    if (!NfcTagWriter.available(this)) {
        Log.w(TAG, "nfc hardware unavailable or disabled")
        toast(t("nfc.unavailable"))
        return
    }
    nfcWriteDialog?.dismiss()
    val dialog = ThemeUi.showSheet(
        this, palette, t("nfc.write.title"), t("nfc.write.hint"),
        cancelLabel = t("cancel")
    ) {
        addView(ThemeUi.text(this@showNfcWriteSheet, currentItem?.name.orEmpty(), 13f, palette, bold = true))
        addView(ThemeUi.text(this@showNfcWriteSheet, uri, 10f, palette, role = ThemeUi.ROLE_SUB))
    }
    dialog.setOnDismissListener {
        if (pendingNfcWriteUri == uri) cancelNfcWrite()
    }
    nfcWriteDialog = dialog
    pendingNfcWriteUri = uri
    dialog.show()
    if (!nfcReaderEnabled) enableNfcForegroundDispatch()
}

internal fun MainActivity.enableNfcForegroundDispatch() {
    val adapter = NfcAdapter.getDefaultAdapter(this) ?: return
    if (!adapter.isEnabled) return
    val writing = AtomicBoolean(false)
    runCatching {
        adapter.enableReaderMode(this, { tag ->
            val uri = pendingNfcWriteUri
            if (uri != null && writing.compareAndSet(false, true)) {
                val result = runCatching { NfcTagWriter.write(tag, uri) }
                runOnUiThread {
                    if (pendingNfcWriteUri == uri) {
                        result.onSuccess {
                            toast(t("nfc.write.done"))
                            finishNfcWrite()
                        }.onFailure {
                            Log.e(TAG, "tag write failed", it)
                            toast(t(it.message?.takeIf { key -> key.startsWith("nfc.") } ?: "nfc.write.failed"))
                        }
                    }
                    writing.set(false)
                }
            } else if (uri == null) {
                val address = Ndef.get(tag)?.cachedNdefMessage?.records?.firstOrNull()?.toUri()
                if (address?.scheme.equals(NfcShortcut.SCHEME, ignoreCase = true)) {
                    val intent = Intent(NfcAdapter.ACTION_NDEF_DISCOVERED, address)
                    runOnUiThread {
                        if (pageActive && pendingNfcWriteUri == null) {
                            if (bound) handleNfcShortcutIntent(intent) else pendingNfcShortcutIntent = intent
                        }
                    }
                }
            }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NFC_BARCODE, null)
    }.onSuccess { nfcReaderEnabled = true }.onFailure {
        Log.e(TAG, "enableReaderMode failed", it)
        if (pendingNfcWriteUri != null) {
            toast(t("nfc.unavailable"))
            nfcWriteDialog?.dismiss()
        }
    }
}

internal fun MainActivity.disableNfcForegroundDispatch() {
    val adapter = NfcAdapter.getDefaultAdapter(this) ?: return
    runCatching { adapter.disableReaderMode(this) }
        .onFailure { Log.w(TAG, "disableReaderMode failed: ${it.message}") }
    nfcReaderEnabled = false
}

internal fun MainActivity.finishNfcWrite() {
    pendingNfcWriteUri = null
    nfcWriteDialog?.dismiss()
    nfcWriteDialog = null
}

internal fun MainActivity.cancelNfcWrite() {
    if (pendingNfcWriteUri == null && nfcWriteDialog == null) return
    pendingNfcWriteUri = null
    nfcWriteDialog = null
}

internal fun MainActivity.buildCurrentNfcShortcut(): NfcShortcut.Target {
    if (isLive) {
        val parts = currentStationId.split("/", limit = 2)
        require(parts.size == 2) { "nfc.unsupported" }
        return NfcShortcut.Fm(parts[0], parts[1])
    }
    val item = currentItem ?: throw IllegalStateException("nfc.empty")
    val list = currentNfcList(item)
    val online = stableOnlineItem(item)?.let(::onlineTarget) ?: onlineTarget(item)
    when (online) {
        is NfcShortcut.PluginSong -> return online.copy(list = list)
        is NfcShortcut.ProviderSong -> return online.copy(list = list)
        else -> Unit
    }
    if (isLocalMediaStoreItem(item)) return NfcShortcut.LocalSong(item.id, list)
    throw IllegalStateException("nfc.unsupported")
}

private fun MainActivity.findOnlineItem(target: NfcShortcut.Target): MusicItem? =
    (onlineQueue + musicStore.onlineCached()).firstOrNull { item ->
        when (target) {
            is NfcShortcut.PluginSong -> onlineTarget(item) == target.copy(list = null)
            is NfcShortcut.ProviderSong -> onlineTarget(item) == target.copy(list = null)
            else -> false
        }
    }

private fun MainActivity.currentNfcList(item: MusicItem): String? {
    if (currentQueue.none { it.id == item.id } || currentQueue.map { it.id } != queue.map { it.id }) return null
    return when (val index = musicStore.playlistIndex) {
        -1 -> "all"
        FAVORITES_INDEX -> "favorites"
        else -> musicStore.playlists.getOrNull(index)?.name?.takeIf { name ->
            name.length <= 128 && musicStore.playlists.count { it.name == name } == 1
        }?.let { "playlist:$it" }
    }
}

private fun MainActivity.restoreNfcList(list: String, item: MusicItem): Boolean {
    val index = when (list) {
        "all" -> -1
        "favorites" -> FAVORITES_INDEX
        else -> {
            val matches = musicStore.playlists.withIndex().filter { it.value.name == list.removePrefix("playlist:") }
            if (matches.size != 1) return false
            matches.single().index
        }
    }
    val oldIndex = musicStore.playlistIndex
    val oldQuery = musicStore.query
    musicStore.playlistIndex = index
    musicStore.query = ""
    val restored = musicStore.view(songs)
    val containsTarget = restored.any { it.uri == item.uri }
    if (!containsTarget) {
        musicStore.playlistIndex = oldIndex
        musicStore.query = oldQuery
        return false
    }
    queue = restored
    onlineQueue = emptyList()
    onlineQueueProvider = ""
    musicStore.clearOnlineQueue()
    return true
}

private fun MainActivity.stableOnlineItem(item: MusicItem): MusicItem? =
    (onlineQueue + musicStore.onlineCached()).firstOrNull {
        it.id == item.id && ProviderTrackRef.isOnline(it)
    }

private fun onlineTarget(item: MusicItem): NfcShortcut.Target? {
    if (!ProviderTrackRef.isOnline(item)) return null
    val ref = Uri.parse(item.uri)
    val source = ref.getQueryParameter("source").orEmpty()
    val trackId = ref.getQueryParameter("id").orEmpty()
    if (source.isBlank() || trackId.isBlank()) return null
    return when (ref.host) {
        "plugin" -> NfcShortcut.PluginSong(source, trackId)
        "provider" -> NfcShortcut.ProviderSong(
            source, trackId, ref.getQueryParameter("audio").orEmpty()
        )
        else -> null
    }
}

private fun isLocalMediaStoreItem(item: MusicItem): Boolean {
    if (item.id <= 0L || !item.uri.startsWith("content://media/")) return false
    return runCatching { ContentUris.parseId(Uri.parse(item.uri)) == item.id }.getOrDefault(false)
}

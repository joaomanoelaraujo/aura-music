package com.aura.desktop.system

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

/** Windows-only touches: media keys anywhere in the system, and a dark title bar. No-ops elsewhere. */
object WindowsIntegration {
    val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

    enum class MediaKey { PlayPause, Next, Previous, Stop }

    private const val VK_MEDIA_NEXT_TRACK = 0xB0
    private const val VK_MEDIA_PREV_TRACK = 0xB1
    private const val VK_MEDIA_STOP = 0xB2
    private const val VK_MEDIA_PLAY_PAUSE = 0xB3
    private const val WM_HOTKEY = 0x0312
    private const val MOD_NOREPEAT = 0x4000

    @Volatile private var thread: Thread? = null

    /**
     * Listens for the keyboard's media keys while AURA runs, even when its window isn't focused.
     * Keys another app already holds are skipped. Returns how many keys were registered.
     */
    fun listenToMediaKeys(onKey: (MediaKey) -> Unit): Int {
        if (!isWindows || thread != null) return 0
        val keys = mapOf(1 to (VK_MEDIA_PLAY_PAUSE to MediaKey.PlayPause), 2 to (VK_MEDIA_NEXT_TRACK to MediaKey.Next), 3 to (VK_MEDIA_PREV_TRACK to MediaKey.Previous), 4 to (VK_MEDIA_STOP to MediaKey.Stop))
        val registered = java.util.concurrent.CompletableFuture<Int>()
        thread = Thread({
            // Hotkeys belong to the thread that registers them; that thread then pumps their messages.
            val count = keys.count { (id, key) -> User32.INSTANCE.RegisterHotKey(null, id, MOD_NOREPEAT, key.first) }
            registered.complete(count)
            if (count == 0) return@Thread
            val msg = WinUser.MSG()
            while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                if (msg.message == WM_HOTKEY) keys[msg.wParam.toInt()]?.second?.let(onKey)
            }
        }, "aura-media-keys").apply {
            isDaemon = true
            start()
        }
        return runCatching { registered.get(2, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(0)
    }

    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: Dwm? by lazy { if (isWindows) runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull() else null }

    /** Asks Windows 10/11 for a dark title bar on [windowHandle] (matches AURA's dark theme). */
    fun useDarkTitleBar(windowHandle: Long) {
        val api = dwm ?: return
        val hwnd = WinDef.HWND(Pointer(windowHandle))
        // 20 = DWMWA_USE_IMMERSIVE_DARK_MODE (Windows 11 / 10 20H1+); 19 on older Windows 10 builds.
        if (api.DwmSetWindowAttribute(hwnd, 20, IntByReference(1), 4) != 0) {
            api.DwmSetWindowAttribute(hwnd, 19, IntByReference(1), 4)
        }
    }
}

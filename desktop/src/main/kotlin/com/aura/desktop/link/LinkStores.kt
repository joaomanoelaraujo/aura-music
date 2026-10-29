package com.aura.desktop.link

import com.aura.desktop.data.JsonFiles
import com.aura.desktop.data.JsonStore
import com.aura.music.link.LinkPermissions
import com.aura.music.link.PairedDevice
import com.aura.music.link.crypto.SoftwareIdentity
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.UUID

/**
 * Keeps small secrets on disk protected by the operating system: on Windows with DPAPI (only this
 * Windows user can decrypt them); elsewhere in a file only the owner can read.
 */
object SecretStore {
    private val windows = System.getProperty("os.name").lowercase().contains("win")

    fun write(path: Path, secret: ByteArray) {
        Files.createDirectories(path.parent)
        val data = if (windows) Crypt32Util.cryptProtectData(secret) else secret
        val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.write(temp, data)
        if (!windows) runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")) }
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun read(path: Path): ByteArray? {
        if (!Files.isRegularFile(path)) return null
        val data = Files.readAllBytes(path)
        return if (windows) runCatching { Crypt32Util.cryptUnprotectData(data) }.getOrNull() else data
    }
}

@Serializable
data class LocalDeviceInfo(val deviceId: String, val name: String, val publicKey: String)

/** This computer's AURA Link identity: a random id, a name and a key pair created on first use. */
class LocalIdentityStore(private val dir: Path) {
    private val infoFile = dir.resolve("device.json")
    private val keyFile = dir.resolve("identity.key")

    fun load(defaultName: String): Pair<LocalDeviceInfo, SoftwareIdentity> {
        val info = JsonFiles.read(infoFile, LocalDeviceInfo.serializer())
        val privateKey = SecretStore.read(keyFile)
        if (info != null && privateKey != null) {
            runCatching { SoftwareIdentity.restore(privateKey, Base64.getDecoder().decode(info.publicKey)) }.getOrNull()?.let { return info to it }
        }
        // First use (or the protected key can't be read any more, e.g. another Windows user):
        // a new identity. Devices paired with the old one will need pairing again.
        val identity = SoftwareIdentity.generate()
        val fresh = LocalDeviceInfo(UUID.randomUUID().toString(), info?.name ?: defaultName, Base64.getEncoder().encodeToString(identity.publicKey))
        SecretStore.write(keyFile, identity.privateKeyBytes())
        JsonFiles.write(infoFile, LocalDeviceInfo.serializer(), fresh)
        return fresh to identity
    }

    fun rename(info: LocalDeviceInfo, name: String): LocalDeviceInfo {
        val renamed = info.copy(name = name.trim().take(40).ifEmpty { info.name })
        JsonFiles.write(infoFile, LocalDeviceInfo.serializer(), renamed)
        return renamed
    }
}

/** Devices paired with this computer (their public keys and what they may do). */
class PairedDeviceStore(scope: CoroutineScope, path: Path) :
    JsonStore<List<PairedDevice>>(scope, path, ListSerializer(PairedDevice.serializer()), emptyList(), debounceMs = 100) {

    fun find(deviceId: String): PairedDevice? = value.value.firstOrNull { it.deviceId == deviceId }

    fun add(device: PairedDevice) = update { list -> list.filterNot { it.deviceId == device.deviceId } + device }

    fun rename(deviceId: String, name: String) = edit(deviceId) { it.copy(name = name.trim().take(40).ifEmpty { it.name }) }

    fun setPermissions(deviceId: String, permissions: LinkPermissions) = edit(deviceId) { it.copy(permissions = permissions) }

    fun seen(deviceId: String, address: String?) = edit(deviceId) { it.copy(lastAddress = address ?: it.lastAddress, lastSeen = System.currentTimeMillis()) }

    fun remove(deviceId: String) {
        update { list -> list.filterNot { it.deviceId == deviceId } }
        flush()
    }

    private fun edit(deviceId: String, transform: (PairedDevice) -> PairedDevice) = update { list ->
        list.map { if (it.deviceId == deviceId) transform(it) else it }
    }
}

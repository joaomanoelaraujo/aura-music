package com.aura.music.link

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.aura.music.link.crypto.DeviceIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * The phone's AURA Link identity: an EC P-256 key created inside the Android Keystore. The private
 * key never leaves it; signing happens there.
 */
class KeystoreIdentity private constructor(private val alias: String) : DeviceIdentity {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override val publicKey: ByteArray = keyStore.getCertificate(alias).publicKey.encoded

    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyStore.getKey(alias, null) as PrivateKey)
        update(data)
        sign()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "aura-link-identity-v1"

        fun loadOrCreate(): KeystoreIdentity {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
                    initialize(
                        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .build(),
                    )
                }.generateKeyPair()
            }
            return KeystoreIdentity(ALIAS)
        }

        /** Forgets the key (used when the user removes all links and resets the identity). */
        fun delete() {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        }
    }
}

@Serializable
private data class LocalInfo(val deviceId: String, val name: String)

/**
 * This phone's AURA Link id and name, plus the computers it is paired with. Paired devices hold
 * only public keys and names, so they live in the app's private files (not the Keystore).
 */
@Singleton
class LinkStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir = File(context.filesDir, "link").apply { mkdirs() }
    private val infoFile = File(dir, "device.json")
    private val devicesFile = File(dir, "devices.json")
    private val pausedFile = File(dir, "paused.txt")
    private val json = LinkProtocol.json

    @Volatile private var info: LocalInfo = load()

    val deviceId: String get() = info.deviceId
    val deviceName: String get() = info.name

    val identity: KeystoreIdentity by lazy { KeystoreIdentity.loadOrCreate() }

    private fun load(): LocalInfo = runCatching { json.decodeFromString(LocalInfo.serializer(), infoFile.readText()) }.getOrNull()
        ?: LocalInfo(UUID.randomUUID().toString(), defaultName()).also { write(infoFile, json.encodeToString(LocalInfo.serializer(), it)) }

    private fun defaultName(): String {
        val user = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        return (user?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER.replaceFirstChar(Char::uppercase)} ${Build.MODEL}").take(40)
    }

    fun rename(name: String) {
        info = info.copy(name = name.trim().take(40).ifEmpty { info.name })
        write(infoFile, json.encodeToString(LocalInfo.serializer(), info))
    }

    @Synchronized
    fun devices(): List<PairedDevice> =
        runCatching { json.decodeFromString(ListSerializer(PairedDevice.serializer()), devicesFile.readText()) }.getOrDefault(emptyList())

    @Synchronized
    fun save(devices: List<PairedDevice>) = write(devicesFile, json.encodeToString(ListSerializer(PairedDevice.serializer()), devices))

    @Synchronized
    fun upsert(device: PairedDevice) = save(devices().filterNot { it.deviceId == device.deviceId } + device)

    @Synchronized
    fun remove(deviceId: String) = save(devices().filterNot { it.deviceId == deviceId })

    @Synchronized
    fun update(deviceId: String, transform: (PairedDevice) -> PairedDevice) = save(devices().map { if (it.deviceId == deviceId) transform(it) else it })

    /** Computers the user disconnected for now (no automatic reconnection until resumed). */
    @Synchronized
    fun isPaused(deviceId: String): Boolean = deviceId in paused()

    @Synchronized
    fun setPaused(deviceId: String, value: Boolean) {
        val set = paused().toMutableSet().apply { if (value) add(deviceId) else remove(deviceId) }
        write(pausedFile, set.joinToString(separator = System.lineSeparator()))
    }

    private fun paused(): Set<String> = runCatching { pausedFile.readLines().filter(String::isNotBlank).toSet() }.getOrDefault(emptySet())

    private fun write(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    fun local(capabilities: List<String>) = LocalDevice(deviceId, deviceName, Platform.Android, identity, capabilities)
}

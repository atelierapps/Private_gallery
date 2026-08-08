package com.atelierapps.vault.crypto

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.atelierapps.vault.VaultGraph
import java.io.IOException
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ExoPlayer [DataSource] that decrypts a CTR video envelope on the fly (spec §8,
 * §9). No plaintext ever hits disk — ciphertext is read from `vault/<id>` and
 * decrypted in memory as ExoPlayer pulls it.
 *
 * Seeking works because CTR is random-access: on each [open] (ExoPlayer reopens
 * to seek — e.g. to read the MP4 moov atom at the end, then back to the start)
 * the counter is advanced to the requested byte offset via [CtrCounter],
 * mirroring the math proven in the crypto harness. The per-file DEK is unwrapped
 * once and cached ([DekCache]), so repeated seeks don't re-pay RSA.
 *
 * URI form: `vaultmedia://<id>`.
 */
@UnstableApi
class VaultCtrDataSource(private val context: Context) : BaseDataSource(/* isNetwork = */ false) {

    private var uri: Uri? = null
    private var raf: RandomAccessFile? = null
    private var cipher: Cipher? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val id = dataSpec.uri.lastPathSegment ?: dataSpec.uri.host
            ?: throw IOException("no media id in ${dataSpec.uri}")

        val file = VaultGraph.storage(context).blob(id)
        if (!file.exists()) throw IOException("blob missing: $id")

        val headerLen = EnvelopeFormat.headerLen(EnvelopeFormat.MODE_CTR)
        val header = ByteArray(headerLen)
        RandomAccessFile(file, "r").use { it.readFully(header) }
        if (header[1] != EnvelopeFormat.MODE_CTR) throw IOException("not a CTR blob")
        val wrapped = header.copyOfRange(2, 2 + EnvelopeFormat.WRAPPED_DEK_LEN)
        val iv = header.copyOfRange(2 + EnvelopeFormat.WRAPPED_DEK_LEN, headerLen)

        val dek = DekCache.getOrLoad(file.absolutePath) { VaultKeys.wrapper.unwrap(wrapped) }
        val position = dataSpec.position
        cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"),
                IvParameterSpec(CtrCounter.counterForOffset(iv, position)))
            val skip = CtrCounter.skipWithinBlock(position)
            if (skip > 0) update(ByteArray(skip)) // realign keystream to the byte offset
        }

        raf = RandomAccessFile(file, "r").apply { seek(headerLen + position) }
        val dataLength = file.length() - headerLen
        bytesRemaining =
            if (dataSpec.length != C.LENGTH_UNSET) dataSpec.length else dataLength - position

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val cipherText = ByteArray(toRead)
        val n = raf!!.read(cipherText, 0, toRead)
        if (n == -1) return C.RESULT_END_OF_INPUT
        val plain = cipher!!.update(cipherText, 0, n) // CTR: returns exactly n bytes
        System.arraycopy(plain, 0, buffer, offset, n)
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            raf?.close()
        } finally {
            raf = null
            cipher = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    @UnstableApi
    class Factory(context: Context) : DataSource.Factory {
        private val appContext = context.applicationContext
        override fun createDataSource(): DataSource = VaultCtrDataSource(appContext)
    }

    companion object {
        fun uriFor(id: String): String = "vaultmedia://$id"
    }
}

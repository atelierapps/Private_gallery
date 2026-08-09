package com.atelierapps.vault.ui.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.crypto.MediaCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem

/**
 * Coil [Fetcher] that decrypts a vault image **in memory** and hands the plain
 * bytes to Coil's decoder (spec §8). No plaintext ever touches disk; the
 * [com.atelierapps.vault.ui.image.VaultImageLoader] that installs this fetcher
 * disables Coil's disk cache for the same reason.
 */
class VaultThumbFetcher(
    private val key: VaultMediaKey,
    private val context: coil3.PlatformContext,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val storage = VaultGraph.storage(context)
        val file = if (key.full) storage.blob(key.id) else storage.thumb(key.id)
        val plain = MediaCrypto.decryptGcmFile(file)

        SourceFetchResult(
            source = ImageSource(
                source = Buffer().apply { write(plain) },
                fileSystem = FileSystem.SYSTEM,
            ),
            // Thumbnails are always re-encoded JPEG; full blobs keep their original
            // format (HEIC/PNG/…), so let Coil sniff those from the bytes.
            mimeType = if (key.full) null else "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val context: coil3.PlatformContext) : Fetcher.Factory<VaultMediaKey> {
        override fun create(data: VaultMediaKey, options: Options, imageLoader: ImageLoader): Fetcher =
            VaultThumbFetcher(data, context)
    }
}

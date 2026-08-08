package com.atelierapps.vault.ui.image

import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches a fast MediaStore thumbnail for a device photo or video (importer
 * grid + folder covers). `ContentResolver.loadThumbnail` returns a small,
 * already-downsampled bitmap quickly and works for videos — where Coil's default
 * image decoder would be slow for photos and produce nothing for videos.
 */
class DeviceThumbFetcher(
    private val model: DeviceThumb,
    private val context: coil3.PlatformContext,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val bitmap = context.contentResolver.loadThumbnail(model.uri, Size(384, 384), null)
        ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val context: coil3.PlatformContext) : Fetcher.Factory<DeviceThumb> {
        override fun create(data: DeviceThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            DeviceThumbFetcher(data, context)
    }
}

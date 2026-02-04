package com.example.audioextractor.downloader

import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import java.util.concurrent.TimeUnit

class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: NpRequest): NpResponse {
        val reqBuilder = Request.Builder()
            .url(request.url())
            .method(
                request.httpMethod(),
                request.dataToSend()?.let { okhttp3.RequestBody.create(null, it) }
            )

        // Aggiungi headers
        request.headers().forEach { (key, values) ->
            values.forEach { value -> reqBuilder.addHeader(key, value) }
        }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            val bodyString = resp.body?.string()  // legge il contenuto della risposta

            return NpResponse(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                bodyString,
                request.url()
            )
        }
    }

    companion object {
        fun createDefault(): OkHttpDownloader =
            OkHttpDownloader(
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
            )
    }
}

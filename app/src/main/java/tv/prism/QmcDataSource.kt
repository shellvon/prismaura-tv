package tv.prism

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

internal class QmcDecryptionException(message: String) : IOException(message)

/** Decrypt only the bytes Media3 requested. Every reopened range starts at its own file offset. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class QmcDataSource(private val upstream: DataSource, private val cipher: Qmc2Cipher) : DataSource {
    private var position = 0L
    private var headerSize = 0
    private var checkHeader = false
    private val header = ByteArray(16)

    override fun open(dataSpec: DataSpec): Long {
        position = dataSpec.position
        headerSize = 0
        checkHeader = position == 0L
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val read = upstream.read(buffer, offset, length)
        if (read <= 0) return read
        cipher.decrypt(position, buffer, offset, read)
        position += read
        if (checkHeader) {
            val copy = minOf(read, header.size - headerSize)
            buffer.copyInto(header, headerSize, offset, offset + copy)
            headerSize += copy
            if (headerSize == header.size) validateHeader()
        }
        return read
    }

    private fun validateHeader() {
        fun starts(value: String, at: Int = 0): Boolean = headerSize >= at + value.length && value.indices.all { header[at + it] == value[it].code.toByte() }
        val mpeg = headerSize >= 2 && (header[0].toInt() and 255) == 255 && (header[1].toInt() and 224) == 224
        if (!starts("fLaC") && !starts("OggS") && !starts("ID3") && !starts("ftyp", 4) && !mpeg) {
            throw QmcDecryptionException("解密后的音频格式无法识别，密钥可能与文件不匹配")
        }
        checkHeader = false
        header.fill(0)
    }

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() {
        try { upstream.close() }
        finally { position = 0; headerSize = 0; checkHeader = false; header.fill(0) }
    }
}

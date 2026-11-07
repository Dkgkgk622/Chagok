package com.chagok.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCode {
    /** Quiet zone in modules. 4 is the QR standard; phone cameras miss codes with less on a busy screen. */
    const val QUIET_ZONE = 4

    /**
     * One pixel per module (plus the quiet zone). Draw it scaled by a whole number with no filtering
     * (QrImage in MainActivity): scaling a pre-rendered bitmap by a fraction blurs module edges, and a
     * blurred 141-module code is what phone cameras failed to read. Null when the text is too long.
     */
    fun render(text: String): ImageBitmap? = try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to QUIET_ZONE,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) Color.BLACK else Color.WHITE
        }
        Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    } catch (_: Exception) {
        null // text too long for one QR code
    }
}

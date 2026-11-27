package com.memo.voicestudio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * استخراج النص من PDF: أولًا من طبقة النص مباشرة (PdfBox)،
 * ولو الصفحة صورة (سكانر) نرجّع صورة الصفحة لتُقرأ عبر Gemini.
 */
object PdfText {

    fun extractText(context: Context, file: File): String? {
        return try {
            PDFBoxResourceLoader.init(context)
            PDDocument.load(file).use { doc ->
                // نقرأ أول 10 صفحات بس (نفس حد التطبيق) بدل الملف كله —
                // ملف كبير (مئات الصفحات) كان بيتقرأ كامل قبل ما يبدأ أي حاجة.
                PDFTextStripper().apply {
                    startPage = 1
                    endPage = minOf(doc.numberOfPages, 10)
                }.getText(doc)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun pageCount(file: File): Int {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer -> renderer.pageCount }
            }
        } catch (e: Exception) {
            0
        }
    }

    fun renderPageToBitmap(file: File, page: Int): Bitmap? {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (page < 0 || page >= renderer.pageCount) return null
                    renderer.openPage(page).use { pg ->
                        val bitmap = Bitmap.createBitmap(
                            pg.width * 2,
                            pg.height * 2,
                            Bitmap.Config.ARGB_8888
                        )
                        pg.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}

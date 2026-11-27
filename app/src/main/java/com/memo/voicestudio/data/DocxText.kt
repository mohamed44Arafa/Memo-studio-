package com.memo.voicestudio.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * استخراج النص من ملف Word (DOCX) محليًا وبسرعة — من غير أي طلبات شبكة.
 * ملف الـ DOCX عبارة عن ZIP جواه word/document.xml، والنص موجود في عناصر w:t.
 * من غير أي مكتبة خارجية (ZipInputStream + XmlPullParser من النظام نفسه).
 */
object DocxText {
    private const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun extractText(input: InputStream): String? {
        return try {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        return readDocumentXml(zip)
                    }
                    entry = zip.nextEntry
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readDocumentXml(input: InputStream): String {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, "UTF-8")
        val out = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.namespace == WORD_NS) {
                when (parser.name) {
                    "t" -> out.append(parser.nextText())
                    "tab" -> out.append(" ")
                    "br" -> out.append("\n")
                }
            } else if (event == XmlPullParser.END_TAG &&
                parser.namespace == WORD_NS && parser.name == "p"
            ) {
                out.append("\n")
            }
            event = parser.next()
        }
        return out.toString().trim()
    }
}

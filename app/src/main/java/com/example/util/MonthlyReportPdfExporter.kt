package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.core.model.Student
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

data class MonthlyReportPdfData(
    val student: Student,
    val month: Int,
    val year: Int,
    val monthNameAr: String,
    val attendanceRate: Float,
    val attendancePresent: Int,
    val attendanceAbsent: Int,
    val attendanceTotal: Int,
    val absenceList: List<com.example.core.model.Attendance>,
    val recitationAverage: Float?,
    val recitationCount: Int,
    val recitationList: List<com.example.core.model.Recitation>,
    val examAverage: Float?,
    val examCount: Int,
    val examList: List<com.example.core.model.Exam>,
    val teacherNote: String?
)

object MonthlyReportPdfExporter {

    private const val PAGE_WIDTH = 595 // A4 width in points (72 DPI)
    private const val PAGE_HEIGHT = 842 // A4 height in points
    private const val MARGIN = 40f
    private const val FOOTER_HEIGHT = 36f
    private const val CONTENT_TOP = MARGIN + 10f
    private const val CONTENT_BOTTOM = PAGE_HEIGHT - MARGIN - FOOTER_HEIGHT
    private const val MAX_PAGE_CONTENT_HEIGHT = CONTENT_BOTTOM - CONTENT_TOP

    private class PdfStyles {
        val titleTextPaint = TextPaint().apply {
            color = Color.rgb(26, 82, 118)
            textSize = 20f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val subtitleTextPaint = TextPaint().apply {
            color = Color.rgb(93, 109, 126)
            textSize = 11.5f
            isAntiAlias = true
        }

        val sectionTextPaint = TextPaint().apply {
            color = Color.rgb(33, 97, 140)
            textSize = 14f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val labelTextPaint = TextPaint().apply {
            color = Color.rgb(41, 128, 185)
            textSize = 12f
            isAntiAlias = true
        }

        val valueTextPaint = TextPaint().apply {
            color = Color.rgb(40, 55, 71)
            textSize = 12f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val boxPaint = Paint().apply {
            color = Color.rgb(242, 244, 244)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val rowAltPaint = Paint().apply {
            color = Color.rgb(248, 250, 252)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val borderPaint = Paint().apply {
            color = Color.rgb(174, 182, 191)
            style = Paint.Style.STROKE
            strokeWidth = 1f
            isAntiAlias = true
        }

        val linePaint = Paint().apply {
            color = Color.rgb(174, 182, 191)
            strokeWidth = 1.5f
        }
    }

    private interface PdfBlock {
        val height: Float
        val keepWithNext: Boolean get() = false
        val continuationHeader: PdfBlock? get() = null
        fun draw(canvas: Canvas, topY: Float, usableWidth: Int, styles: PdfStyles)
    }

    suspend fun generateMonthlyReportPdf(
        context: Context,
        reportData: MonthlyReportPdfData
    ): File? {
        val appContext = context.applicationContext

        return withContext(Dispatchers.Default) {
            val pdfDocument = PdfDocument()
            try {
                val styles = PdfStyles()
                val usableWidth = (PAGE_WIDTH - (2 * MARGIN)).toInt()
                val blocks = buildBlocks(reportData, usableWidth, styles)
                val pages = paginateBlocks(blocks, MAX_PAGE_CONTENT_HEIGHT)
                val totalPages = pages.size.coerceAtLeast(1)

                pages.forEachIndexed { pageIndex, pageBlocks ->
                    ensureActive()
                    val pageNumber = pageIndex + 1
                    val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                    val page = pdfDocument.startPage(pageInfo)
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)

                    var cursorY = CONTENT_TOP
                    for (block in pageBlocks) {
                        block.draw(canvas, cursorY, usableWidth, styles)
                        cursorY += block.height
                    }

                    drawFooter(canvas, usableWidth, styles, pageNumber, totalPages)
                    pdfDocument.finishPage(page)
                }

                ensureActive()

                withContext(Dispatchers.IO) {
                    val sanitizedName = reportData.student.fullName.replace(Regex("[^a-zA-Z0-9\u0600-\u06FF]"), "_")
                    val outputFile = File(appContext.cacheDir, "monthly_report_${reportData.year}_${reportData.month}_$sanitizedName.pdf")
                    FileOutputStream(outputFile).use { fos ->
                        pdfDocument.writeTo(fos)
                        fos.flush()
                    }
                    outputFile
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } finally {
                try {
                    pdfDocument.close()
                } catch (_: Exception) {}
            }
        }
    }

    private fun buildBlocks(
        data: MonthlyReportPdfData,
        usableWidth: Int,
        styles: PdfStyles
    ): List<PdfBlock> {
        val blocks = mutableListOf<PdfBlock>()
        val colWidth = (usableWidth / 2) - 15
        val rightColX = PAGE_WIDTH - MARGIN - 15f
        val leftColX = MARGIN + 15f

        // 1. Report Header
        blocks += object : PdfBlock {
            override val height = 74f
            override fun draw(canvas: Canvas, topY: Float, usableWidth: Int, styles: PdfStyles) {
                drawCenteredStatic(canvas, "منصة مدار للمدرس", styles.titleTextPaint, topY, usableWidth)
                drawCenteredStatic(canvas, "التقرير الشهري لأداء الطالب", styles.subtitleTextPaint, topY + 28f, usableWidth)
                canvas.drawLine(MARGIN, topY + 56f, PAGE_WIDTH - MARGIN, topY + 56f, styles.linePaint)
            }
        }

        // 2. Student & Period Info Box
        val nameLayout = buildLeftLayout(data.student.fullName, styles.valueTextPaint, colWidth)
        val periodLayout = buildLeftLayout("${data.monthNameAr} (${data.year})", styles.valueTextPaint, colWidth)
        val infoRow1Height = maxOf(22f, nameLayout.height.toFloat() + 6f)
        val infoRow2Height = maxOf(22f, periodLayout.height.toFloat() + 6f)
        val infoBoxHeight = 24f + infoRow1Height + infoRow2Height
        blocks += object : PdfBlock {
            override val height = infoBoxHeight + 18f
            override fun draw(canvas: Canvas, topY: Float, usableWidth: Int, styles: PdfStyles) {
                val rect = RectF(MARGIN, topY, PAGE_WIDTH - MARGIN, topY + infoBoxHeight)
                canvas.drawRoundRect(rect, 8f, 8f, styles.boxPaint)
                canvas.drawRoundRect(rect, 8f, 8f, styles.borderPaint)

                val row1Y = topY + 12f
                drawRightStatic(canvas, "اسم الطالب:", styles.labelTextPaint, rightColX, row1Y, colWidth)
                drawLayoutAt(canvas, nameLayout, leftColX, row1Y)

                val row2Y = row1Y + infoRow1Height
                drawRightStatic(canvas, "الشهر / السنة:", styles.labelTextPaint, rightColX, row2Y, colWidth)
                drawLayoutAt(canvas, periodLayout, leftColX, row2Y)
            }
        }

        // 3. Metrics Summary Box
        val attendanceStr = if (data.attendanceTotal > 0) {
            String.format(Locale.ENGLISH, "%.1f%% (%d من %d)", data.attendanceRate, data.attendancePresent, data.attendanceTotal)
        } else {
            "لا توجد أيام مسجلة"
        }
        val recitationStr = if (data.recitationCount > 0 && data.recitationAverage != null) {
            String.format(Locale.ENGLISH, "%.1f%% (%d تسميع)", data.recitationAverage, data.recitationCount)
        } else {
            "لا توجد اختبارات تسميع"
        }
        val examStr = if (data.examCount > 0 && data.examAverage != null) {
            String.format(Locale.ENGLISH, "%.1f%% (%d اختبار)", data.examAverage, data.examCount)
        } else {
            "لا توجد اختبارات مسجلة"
        }

        blocks += SectionHeaderBlock("إحصائيات الأداء الشهرية", keepWithNext = true)
        blocks += object : PdfBlock {
            val metricsBoxHeight = 108f
            override val height = metricsBoxHeight + 18f
            override fun draw(canvas: Canvas, topY: Float, usableWidth: Int, styles: PdfStyles) {
                val rect = RectF(MARGIN, topY, PAGE_WIDTH - MARGIN, topY + metricsBoxHeight)
                canvas.drawRoundRect(rect, 8f, 8f, styles.boxPaint)
                canvas.drawRoundRect(rect, 8f, 8f, styles.borderPaint)

                var metricY = topY + 14f
                drawRightStatic(canvas, "نسبة الحضور:", styles.labelTextPaint, rightColX, metricY, colWidth)
                drawLeftStatic(canvas, attendanceStr, styles.valueTextPaint, leftColX, metricY, colWidth)

                metricY += 30f
                drawRightStatic(canvas, "معدل التسميع:", styles.labelTextPaint, rightColX, metricY, colWidth)
                drawLeftStatic(canvas, recitationStr, styles.valueTextPaint, leftColX, metricY, colWidth)

                metricY += 30f
                drawRightStatic(canvas, "متوسط الامتحانات:", styles.labelTextPaint, rightColX, metricY, colWidth)
                drawLeftStatic(canvas, examStr, styles.valueTextPaint, leftColX, metricY, colWidth)
            }
        }

        // 4. Teacher Note Section (dynamic height + multi-paragraph splitting if unusually huge)
        val noteText = data.teacherNote?.trim().orEmpty()
        if (noteText.isNotEmpty()) {
            blocks += SectionHeaderBlock("ملاحظات المدرس:", keepWithNext = true)
            val noteInnerWidth = usableWidth - 30
            val fullLayout = buildRightLayout(noteText, styles.valueTextPaint, noteInnerWidth)
            val fullNoteBoxHeight = maxOf(48f, fullLayout.height.toFloat() + 24f)
            if (fullNoteBoxHeight + 18f <= MAX_PAGE_CONTENT_HEIGHT - 40f) {
                blocks += NoteBoxBlock(fullLayout, fullNoteBoxHeight)
            } else {
                val paragraphs = noteText.split('\n').flatMap { line ->
                    if (line.length <= 450) listOf(line)
                    else line.chunked(450)
                }.filter { it.isNotBlank() }
                for (chunk in paragraphs) {
                    val chunkLayout = buildRightLayout(chunk, styles.valueTextPaint, noteInnerWidth)
                    val chunkBoxHeight = maxOf(40f, chunkLayout.height.toFloat() + 20f)
                    blocks += NoteBoxBlock(chunkLayout, chunkBoxHeight)
                }
            }
        }

        // 5. Absence Details
        if (data.absenceList.isNotEmpty()) {
            val header = SectionHeaderBlock("أيام الغياب (${data.absenceList.size}):", keepWithNext = true)
            val contHeader = SectionHeaderBlock("أيام الغياب (تابع):", keepWithNext = true)
            blocks += header
            data.absenceList.forEachIndexed { idx, absence ->
                val noteSuffix = absence.note?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
                blocks += TableRowBlock(
                    text = "${absence.date}$noteSuffix",
                    isAlternate = idx % 2 == 1,
                    continuationHeader = contHeader,
                    usableWidth = usableWidth,
                    styles = styles
                )
            }
        }

        // 6. Recitations Table
        if (data.recitationList.isNotEmpty()) {
            val header = SectionHeaderBlock("سجل التسميع (${data.recitationList.size}):", keepWithNext = true)
            val contHeader = SectionHeaderBlock("سجل التسميع (تابع):", keepWithNext = true)
            blocks += header
            data.recitationList.forEachIndexed { idx, rec ->
                val datePrefix = rec.date.takeIf { it.isNotBlank() }?.let { "$it • " }.orEmpty()
                val recStr = "$datePrefix${rec.title}: ${rec.score} / ${rec.maxScore}"
                blocks += TableRowBlock(
                    text = recStr,
                    isAlternate = idx % 2 == 1,
                    continuationHeader = contHeader,
                    usableWidth = usableWidth,
                    styles = styles
                )
            }
        }

        // 7. Exams Table
        if (data.examList.isNotEmpty()) {
            val header = SectionHeaderBlock("سجل الامتحانات (${data.examList.size}):", keepWithNext = true)
            val contHeader = SectionHeaderBlock("سجل الامتحانات (تابع):", keepWithNext = true)
            blocks += header
            data.examList.forEachIndexed { idx, exam ->
                val datePrefix = exam.date.takeIf { it.isNotBlank() }?.let { "$it • " }.orEmpty()
                val examStr = "$datePrefix${exam.examName}: ${exam.score} / ${exam.maxScore}"
                blocks += TableRowBlock(
                    text = examStr,
                    isAlternate = idx % 2 == 1,
                    continuationHeader = contHeader,
                    usableWidth = usableWidth,
                    styles = styles
                )
            }
        }

        return blocks
    }

    private fun paginateBlocks(
        blocks: List<PdfBlock>,
        maxContentHeight: Float
    ): List<List<PdfBlock>> {
        if (blocks.isEmpty()) return listOf(emptyList())

        val pages = mutableListOf<MutableList<PdfBlock>>()
        var currentPage = mutableListOf<PdfBlock>()
        var usedHeight = 0f

        var i = 0
        while (i < blocks.size) {
            val block = blocks[i]
            val nextHeight = if (block.keepWithNext && i + 1 < blocks.size) blocks[i + 1].height else 0f
            val requiredHeight = block.height + nextHeight

            if (currentPage.isNotEmpty() && usedHeight + requiredHeight > maxContentHeight) {
                pages += currentPage
                currentPage = mutableListOf()
                usedHeight = 0f

                val contHeader = block.continuationHeader
                if (contHeader != null && contHeader.height + block.height <= maxContentHeight) {
                    currentPage += contHeader
                    usedHeight += contHeader.height
                }
            }

            currentPage += block
            usedHeight += block.height
            i++
        }

        if (currentPage.isNotEmpty()) {
            pages += currentPage
        }
        return pages
    }

    internal fun estimatePageCount(blockHeights: List<Float>, maxPageHeight: Float = MAX_PAGE_CONTENT_HEIGHT): Int {
        if (blockHeights.isEmpty()) return 1
        var pages = 1
        var used = 0f
        for (h in blockHeights) {
            if (used > 0f && used + h > maxPageHeight) {
                pages++
                used = 0f
            }
            used += h
        }
        return pages
    }

    private class SectionHeaderBlock(
        private val title: String,
        override val keepWithNext: Boolean
    ) : PdfBlock {
        override val height: Float = 28f
        override fun draw(canvas: Canvas, topY: Float, usableWidth: Int, styles: PdfStyles) {
            drawRightStatic(canvas, title, styles.sectionTextPaint, PAGE_WIDTH - MARGIN, topY + 4f, usableWidth)
        }
    }

    private class NoteBoxBlock(
        private val layout: StaticLayout,
        private val boxHeight: Float
    ) : PdfBlock {
        override val height: Float = boxHeight + 16f
        override fun draw(canvas: Canvas, topY: Float, usableWidth: Int, styles: PdfStyles) {
            val rect = RectF(MARGIN, topY, PAGE_WIDTH - MARGIN, topY + boxHeight)
            canvas.drawRoundRect(rect, 8f, 8f, styles.boxPaint)
            canvas.drawRoundRect(rect, 8f, 8f, styles.borderPaint)

            canvas.save()
            canvas.translate(MARGIN + 15f, topY + 12f)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private class TableRowBlock(
        text: String,
        private val isAlternate: Boolean,
        override val continuationHeader: PdfBlock?,
        usableWidth: Int,
        styles: PdfStyles
    ) : PdfBlock {
        private val rowLayout = buildRightLayout(text, styles.valueTextPaint, usableWidth - 24)
        private val rowHeight = maxOf(22f, rowLayout.height.toFloat() + 8f)
        override val height: Float = rowHeight + 4f

        override fun draw(canvas: Canvas, topY: Float, usableWidth: Int, styles: PdfStyles) {
            val rect = RectF(MARGIN, topY, PAGE_WIDTH - MARGIN, topY + rowHeight)
            if (isAlternate) {
                canvas.drawRoundRect(rect, 6f, 6f, styles.rowAltPaint)
            }
            canvas.drawRoundRect(rect, 6f, 6f, styles.borderPaint)

            canvas.save()
            canvas.translate(MARGIN + 12f, topY + 4f)
            rowLayout.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawFooter(
        canvas: Canvas,
        usableWidth: Int,
        styles: PdfStyles,
        pageNumber: Int,
        totalPages: Int
    ) {
        val footerTop = PAGE_HEIGHT - MARGIN - 16f
        canvas.drawLine(MARGIN, footerTop - 8f, PAGE_WIDTH - MARGIN, footerTop - 8f, styles.linePaint)
        val footerText = "تم استخراج هذا التقرير إلكترونياً من تطبيق مدار للمدرس • صفحة $pageNumber من $totalPages"
        drawCenteredStatic(canvas, footerText, styles.subtitleTextPaint, footerTop, usableWidth)
    }

    private fun buildRightLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
            .setTextDirection(android.text.TextDirectionHeuristics.RTL)
            .build()
    }

    private fun buildLeftLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(android.text.TextDirectionHeuristics.LTR)
            .build()
    }

    private fun drawLayoutAt(canvas: Canvas, layout: StaticLayout, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawCenteredStatic(canvas: Canvas, text: String, paint: TextPaint, y: Float, usableWidth: Int) {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, usableWidth.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(android.text.TextDirectionHeuristics.RTL)
            .build()
        canvas.save()
        canvas.translate(MARGIN, y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawRightStatic(canvas: Canvas, text: String, paint: TextPaint, x: Float, y: Float, width: Int) {
        val safeWidth = width.coerceAtLeast(1)
        val layout = buildRightLayout(text, paint, safeWidth)
        canvas.save()
        canvas.translate(x - safeWidth, y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawLeftStatic(canvas: Canvas, text: String, paint: TextPaint, x: Float, y: Float, width: Int) {
        val layout = buildLeftLayout(text, paint, width)
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }

    fun openPdf(context: Context, pdfFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pdfFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "لا توجد تطبيق مناسب لفتح ملفات PDF على جهازك", Toast.LENGTH_LONG).show()
        }
    }
}

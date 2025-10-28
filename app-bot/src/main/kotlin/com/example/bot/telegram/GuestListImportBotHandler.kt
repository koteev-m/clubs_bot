package com.example.bot.telegram

import com.example.bot.club.GuestListRepository
import com.example.bot.data.club.GuestListCsvParser
import com.example.bot.routes.performGuestListImport
import com.example.bot.routes.toCsv
import com.example.bot.routes.toSummary
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.SendDocument
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import java.io.InputStream
import java.nio.charset.StandardCharsets
import kotlin.io.use

/** Handles guest list import interactions for Telegram users. */
class GuestListImportBotHandler(
    private val repository: GuestListRepository,
    private val parser: GuestListCsvParser,
    private val download: suspend (String) -> InputStream,
    private val send: suspend (Any) -> BaseResponse,
) {
    /** Processes document uploads with captions like `list=42 dry_run`. */
    suspend fun handle(update: Update) {
        val message = update.message() ?: return
        val document = message.document() ?: return
        val caption = message.caption()?.trim() ?: return
        val parts = caption.split(Regex("\\s+")).filter { it.isNotBlank() }
        val listToken = parts.firstOrNull { it.startsWith("list=") } ?: return
        val listId = listToken.removePrefix("list=").toLongOrNull() ?: return
        val dryRun = parts.any { it.equals("dry_run", true) || it.equals("dry-run", true) }
        val chatId = message.chat().id()
        try {
            download(document.fileId()).use { stream ->
                val report = performGuestListImport(repository, parser, listId, stream, dryRun)
                val summary = report.toSummary(dryRun)
                send(SendMessage(chatId, summary))
                if (report.rejected.isNotEmpty()) {
                    val csv = report.toCsv().toByteArray(StandardCharsets.UTF_8)
                    val document = SendDocument(chatId, csv).fileName("import_report.csv")
                    send(document)
                }
            }
        } catch (ex: Throwable) {
            val reason = ex.message ?: "Import failed"
            send(SendMessage(chatId, "Ошибка импорта: $reason"))
        }
    }
}

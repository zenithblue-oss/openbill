package app.openbill.cli

import app.openbill.core.domain.model.Permission
import app.openbill.core.domain.model.RecurringSchedule
import app.openbill.core.domain.usecase.GenerateRecurring
import app.openbill.core.domain.usecase.PostVoucher
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** `recurring list|show|pause|resume|delete|run`: the app's recurring invoice schedules (Money > Recurring). */
fun recurringCommands(cli: CliState) = Group("recurring", "Recurring invoice schedules").subcommands(
    RecurringList(cli), RecurringShow(cli), RecurringToggle(cli, pause = true), RecurringToggle(cli, pause = false), RecurringDelete(cli), RecurringRun(cli),
)

/** `pos held list|discard`: bills put on hold at the POS counter. */
fun posCommands(cli: CliState) = Group("pos", "Point of sale").subcommands(
    Group("held", "Bills on hold at the counter").subcommands(HeldList(cli), HeldDiscard(cli)),
)

private suspend fun Data.findSchedule(ref: String): RecurringSchedule {
    val all = repos.recurring.observeSchedules(firm().id).first()
    return all.firstOrNull { it.id == ref } ?: all.filter { it.name.equals(ref.trim(), true) }.let {
        when (it.size) {
            0 -> fail(Exit.NOT_FOUND, "No recurring schedule '$ref'")
            1 -> it.single()
            else -> fail(Exit.USAGE, "Schedule name '$ref' is ambiguous; use its id")
        }
    }
}

private fun Data.today(): LocalDate = now().toLocalDateTime(TimeZone.currentSystemDefault()).date

private suspend fun Data.scheduleJson(s: RecurringSchedule) = jsonOf(
    "id" to s.id, "name" to s.name, "frequency" to s.frequency.name.lowercase(), "interval" to s.interval,
    "startDate" to s.startDate, "endDate" to s.endDate, "maxCount" to s.maxCount, "nextDate" to s.nextDate,
    "generated" to s.generatedCount, "autoPost" to s.autoPost, "paused" to s.paused, "finished" to s.finished,
    "copyLatest" to s.copyLatest, "template" to (repos.vouchers.findVoucher(s.templateVoucherId)?.number ?: s.templateVoucherId),
    "lastVoucher" to s.lastVoucherId?.let { repos.vouchers.findVoucher(it)?.number ?: it }, "lastError" to s.lastError,
)

private fun RecurringSchedule.state() = when {
    finished -> "finished"
    paused -> "paused"
    lastError != null -> "error"
    else -> "active"
}

private fun RecurringSchedule.every() = if (interval == 1) frequency.label.lowercase() else "every $interval ${frequency.unit}s"

class RecurringList(private val cli: CliState) : CliktCommand("list") {
    override fun help(context: Context) = "List schedules with their next date"
    override fun run() = cli.withData(write = false, Permission.MANAGE_MASTERS) {
        val list = repos.recurring.observeSchedules(firm().id).first()
        when {
            cli.json -> cli.printJson(toJson(list.map { scheduleJson(it) }))
            cli.quiet -> list.forEach { cli.out.println(it.id) }
            else -> cli.printTable(
                listOf(Col("NAME"), Col("REPEATS"), Col("NEXT"), Col("MADE", true), Col("STATE"), Col("ID")),
                list.map { listOf(it.name, it.every(), it.nextDate?.toString() ?: "-", it.generatedCount.toString(), it.state(), it.id) },
            )
        }
    }
}

class RecurringShow(private val cli: CliState) : CliktCommand("show") {
    override fun help(context: Context) = "Show a schedule by id or name"
    private val ref by argument()
    override fun run() = cli.withData(write = false, Permission.MANAGE_MASTERS) {
        val j = scheduleJson(findSchedule(ref))
        if (cli.json) cli.printJson(j) else cli.printFields(j.map { it.key to it.value.toString().trim('"').takeUnless { v -> v == "null" } })
    }
}

/** Pause, or resume (periods that passed while paused are skipped, as in the app). */
class RecurringToggle(private val cli: CliState, private val pause: Boolean) : CliktCommand(if (pause) "pause" else "resume") {
    override fun help(context: Context) = if (pause) "Stop making invoices until resumed" else "Start again from today; periods missed while paused are skipped"
    private val ref by argument()
    override fun run() = cli.withData(write = true, Permission.MANAGE_MASTERS) {
        val s = findSchedule(ref)
        if (s.paused == pause) fail(Exit.CONFLICT, "${s.name} is already ${if (pause) "paused" else "running"}")
        val updated = if (pause) s.copy(paused = true, updatedAt = now()) else GenerateRecurring.resumed(s, today(), now())
        repos.recurring.saveSchedule(updated)
        if (cli.json) cli.printJson(scheduleJson(repos.recurring.findSchedule(s.id)!!)) else cli.out.println("${if (pause) "Paused" else "Resumed"} ${s.name}")
    }
}

class RecurringDelete(private val cli: CliState) : CliktCommand("delete") {
    override fun help(context: Context) = "Delete a schedule; the invoices it made stay"
    private val ref by argument()
    override fun run() = cli.withData(write = true, Permission.MANAGE_MASTERS) {
        val s = findSchedule(ref)
        cli.confirm("Delete schedule ${s.name}? Invoices it made stay.")
        repos.recurring.deleteSchedule(s.id)
        if (cli.json) cli.printJson(jsonOf("id" to s.id, "deleted" to true)) else cli.out.println("Deleted ${s.name}")
    }
}

/**
 * `recurring run [--today DATE]`: makes every invoice due up to today, catching up missed periods,
 * exactly as the app does at start-up. Each period is claimed atomically, so re-running (or the
 * app running at the same time) never bills a period twice.
 */
class RecurringRun(private val cli: CliState) : CliktCommand("run") {
    override fun help(context: Context) = "Make the invoices schedules owe up to today (idempotent; safe in cron)"
    private val asOf by option("--today", help = "YYYY-MM-DD to run as of (default: today)")
    override fun run() = cli.withData(write = true, Permission.CREATE_VOUCHERS) {
        val f = firm()
        val years = repos.firms.observeFinancialYears(f.id).first()
        val before = repos.recurring.observeSchedules(f.id).first().associate { it.id to it.generatedCount }
        val made = GenerateRecurring(repos.recurring, repos.vouchers) { id, at -> PostVoucher(repos.vouchers)(id, at) }
            .invoke(f.id, asOf?.let(::parseDate) ?: today(), now(), cli.env.newId) { d -> years.firstOrNull { d >= it.startDate && d <= it.endDate }?.id }
        val after = repos.recurring.observeSchedules(f.id).first()
        if (cli.json) {
            cli.printJson(jsonOf("made" to made, "schedules" to after.map { jsonOf("id" to it.id, "name" to it.name, "made" to (it.generatedCount - (before[it.id] ?: 0)), "nextDate" to it.nextDate, "error" to it.lastError) }))
        } else {
            if (cli.quiet) cli.out.println(made) else cli.out.println("Made $made invoice(s)")
            after.filter { it.lastError != null }.forEach { cli.env.err.println("warning: ${it.name}: ${it.lastError}") }
        }
    }
}

class HeldList(private val cli: CliState) : CliktCommand("list") {
    override fun help(context: Context) = "Bills on hold, oldest first"
    override fun run() = cli.withData(write = false) {
        val f = firm()
        val held = repos.vouchers.observeHeldBills(f.id).first()
        val names = held.mapNotNull { it.partyId }.distinct().associateWith { repos.parties.findParty(it)?.name }
        when {
            cli.json -> cli.printJson(toJson(held.map { jsonOf("id" to it.id, "heldAt" to it.heldAt?.toString(), "party" to it.partyId?.let(names::get), "lines" to it.lines.size, "total" to it.total) }))
            cli.quiet -> held.forEach { cli.out.println(it.id) }
            else -> cli.printTable(
                listOf(Col("HELD AT"), Col("PARTY"), Col("LINES", true), Col("TOTAL", true), Col("ID")),
                held.map { listOf(it.heldAt.toString(), it.partyId?.let(names::get) ?: "-", it.lines.size.toString(), it.total.indian(), it.id) },
            )
        }
    }
}

/** Discards a held bill as the POS does: it goes to the recycle bin (restorable with `invoice restore`). */
class HeldDiscard(private val cli: CliState) : CliktCommand("discard") {
    override fun help(context: Context) = "Discard a held bill (moves it to the recycle bin)"
    private val id by argument()
    override fun run() = cli.withData(write = true) {
        val v = repos.vouchers.observeHeldBills(firm().id).first().firstOrNull { it.id == id } ?: fail(Exit.NOT_FOUND, "No held bill '$id'")
        cli.confirm("Discard the held bill of ${v.total.indian()}?")
        repos.vouchers.softDeleteDraft(v.id, now())
        if (cli.json) cli.printJson(jsonOf("id" to v.id, "discarded" to true)) else cli.out.println("Discarded ${v.id}")
    }
}

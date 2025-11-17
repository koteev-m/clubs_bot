package com.example.bot.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.JoinType.INNER
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.net.URLEncoder
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.max

/* ==========================  HTML (UI) ========================== */
private val CHECKIN_HTML = """
<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>Night Concierge — Бронирование</title>
  <style>
    :root { --bg:#0f1720; --card:#111a24; --text:#e7eef6; --muted:#9fb3c8; --accent:#56B4FC; --ok:#19c37d; --err:#ff6b6b; }
    *{box-sizing:border-box} body{margin:0;background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,Segoe UI,Roboto,Ubuntu,"Helvetica Neue",Arial}
    .wrap{max-width:760px;margin:0 auto;padding:16px}
    .card{background:var(--card);border-radius:14px;padding:16px 16px 20px 16px;box-shadow:0 8px 24px rgba(0,0,0,.25);margin-bottom:12px}
    h1{font-size:22px;margin:0 0 8px 0} h2{font-size:18px;margin:8px 0}
    .row{display:flex;gap:8px;flex-wrap:wrap}
    select,input,button{border-radius:10px;border:1px solid #233142;background:#0b141d;color:var(--text);padding:10px 12px}
    button{cursor:pointer}
    button.primary{background:var(--accent);border:0;color:#00111f;font-weight:700}
    button.ghost{background:transparent;border:1px solid #2a3a4a}
    .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(90px,1fr));gap:8px}
    .table-card{background:#0b141d;border:1px solid #243244;border-radius:10px;padding:10px;cursor:pointer}
    .table-card.sel{outline:2px solid var(--accent)}
    .muted{color:var(--muted);font-size:13px}
    .ok{color:var(--ok)} .err{color:var(--err)}
    .sep{height:1px;background:#243244;margin:12px 0}
    .tag{display:inline-block;padding:2px 6px;border-radius:6px;background:#0b141d;border:1px solid #2a3a4a;font-size:12px;color:var(--muted)}
    .list-item{padding:10px;border:1px solid #243244;border-radius:10px;background:#0b141d;margin-bottom:8px}
    .list-item b{color:#fff}
  </style>
  <script>
    const tg = window.Telegram?.WebApp;
    if (tg) { try { tg.expand?.(); tg.ready?.(); } catch(e){} }

    const state = {
      tgUser: tg?.initDataUnsafe?.user || null,
      clubs: [],
      events: [],
      tables: [],
      selClub: null,
      selEvent: null,
      guests: 2,
      selTable: null,
      guestName: "",
      phone: "",
      booking: null,
      myBookings: []
    };

    async function apiGet(url){
      const headers = {};
      if (state.tgUser){
        headers["X-TG-User-Id"] = String(state.tgUser.id);
        headers["X-TG-Username"] = state.tgUser.username || "";
        headers["X-TG-Display"] = [(state.tgUser.first_name||""), (state.tgUser.last_name||"")].filter(Boolean).join(" ");
      }
      const r = await fetch(url,{headers});
      if (!r.ok) throw new Error(await r.text());
      return await r.json();
    }
    async function apiPost(url, body){
      const headers = {"Content-Type":"application/json"};
      if (state.tgUser){
        headers["X-TG-User-Id"] = String(state.tgUser.id);
        headers["X-TG-Username"] = state.tgUser.username || ""
        headers["X-TG-Display"] = [(state.tgUser.first_name||""), (state.tgUser.last_name||"")].filter(Boolean).join(" ");
      }
      const r = await fetch(url,{method:"POST",headers,body:JSON.stringify(body)});
      if (!r.ok) throw new Error(await r.text());
      return await r.json();
    }

    function $(id){ return document.getElementById(id); }
    function fmtMoney(x){ return new Intl.NumberFormat('ru-RU',{minimumFractionDigits:2,maximumFractionDigits:2}).format(Number(x)); }
    function note(msg, ok=false){
      const el = $("note"); el.textContent = msg; el.className = ok? "ok": "err"; setTimeout(()=>{ el.textContent=""; }, 4000);
    }

    async function loadClubs(){
      try{
        state.clubs = await apiGet("/api/clubs");
        const s = $("club");
        s.innerHTML = '<option value="">— выберите клуб —</option>';
        state.clubs.forEach(c => {
          const o = document.createElement("option");
          o.value = String(c.id); o.textContent = c.name; s.appendChild(o);
        });
        $("clubs-empty").style.display = state.clubs.length === 0 ? "block" : "none";
      }catch(e){ note(e.message); }
    }

    async function onClubChanged(){
      const id = Number($("club").value || "0");
      state.selClub = id || null;
      state.selEvent = null; $("event").innerHTML = "";
      state.tables = []; state.selTable = null; renderTables();
      if (!id) return;
      try{
        state.events = await apiGet("/api/events?clubId=" + id);
        const s = $("event");
        s.innerHTML = '<option value="">— дата / событие —</option>';
        state.events.forEach(ev=>{
          const start = new Date(ev.startAt);
          const nice = start.toLocaleString("ru-RU", {weekday:"short", day:"2-digit", month:"short", hour:"2-digit", minute:"2-digit"});
          const o = document.createElement("option");
          o.value = String(ev.id);
          o.textContent = nice + (ev.title ? " • " + ev.title : "");
          s.appendChild(o);
        });
      }catch(e){ note(e.message); }
    }

    async function onEventChanged(){
      const id = Number($("event").value || "0");
      state.selEvent = id || null;
      state.selTable = null;
      await reloadFreeTables();
    }

    async function onGuestsChanged(){
      let g = Number($("guests").value || "1");
      if (g<1) g=1; if (g>50) g=50; state.guests=g;
      await reloadFreeTables();
    }

    async function reloadFreeTables(){
      if (!state.selClub || !state.selEvent) { $("tables").innerHTML = ""; return; }
      try{
        state.tables = await apiGet("/api/tables/free?clubId=" + state.selClub + "&eventId=" + state.selEvent + "&guests=" + state.guests);
        state.selTable = null;
        renderTables();
      }catch(e){ note(e.message); }
    }

    function renderTables(){
      const grid = $("tables");
      grid.innerHTML = "";
      state.tables.forEach(t=>{
        const div = document.createElement("div");
        div.className = "table-card" + (state.selTable && state.selTable.id===t.id ? " sel": "");
        div.innerHTML =
          '<div><b>#' + t.tableNumber + '</b> <span class="tag">' + t.capacity + '</span></div>' +
          '<div class="muted">мин. депозит ' + fmtMoney(t.minDeposit) + '</div>';
        div.onclick = ()=>{ state.selTable = t; renderTables(); renderSummary(); };
        grid.appendChild(div);
      });
      renderSummary();
    }

    function renderSummary(){
      const box = $("summary");
      if (!state.selTable){ box.innerHTML = '<span class="muted">Выберите стол.</span>'; return; }
      const total = Number(state.selTable.minDeposit) * state.guests;
      box.innerHTML =
        '<div>Стол <b>#' + state.selTable.tableNumber + '</b> • Гостей: <b>' + state.guests + '</b></div>' +
        '<div class="muted">Мин. депозит за 1 гостя: ' + fmtMoney(state.selTable.minDeposit) + '</div>' +
        '<div><b>Итого депозит: ' + fmtMoney(total) + '</b></div>';
    }

    async function submitBooking(){
      if (!state.selClub || !state.selEvent || !state.selTable) { note("Заполните шаги выше"); return; }
      const name = ($("guestName").value || "").trim();
      const phone = ($("phone").value || "").trim();
      const body = {
        clubId: state.selClub,
        eventId: state.selEvent,
        tableId: state.selTable.id,
        guestsCount: state.guests,
        guestName: name || null,
        phoneE164: phone || null,
        arrivalBy: null
      };
      try{
        const data = await apiPost("/api/bookings", body);
        state.booking = data;
        $("confirm-wrap").style.display="none";
        $("done-wrap").style.display="block";
        $("done-text").innerHTML =
          'Готово! Забронирован стол <b>#' + data.tableNumber + '</b><br/>' +
          'Гостей: <b>' + data.guestsCount + '</b><br/>' +
          'Итого депозит: <b>' + fmtMoney(data.totalDeposit) + '</b>';
        await loadMyBookings();
        if (tg && tg.HapticFeedback?.impactOccurred){ tg.HapticFeedback.impactOccurred("heavy"); }
      }catch(e){ note(e.message); }
    }

    async function loadMyBookings(){
      if (!state.tgUser) return;
      try{
        state.myBookings = await apiGet("/api/bookings/my?tgUserId=" + state.tgUser.id);
        const list = $("my-list");
        list.innerHTML = "";
        state.myBookings.forEach(b=>{
          const dt = new Date(b.eventStartAt).toLocaleString("ru-RU",{day:"2-digit",month:"short",hour:"2-digit",minute:"2-digit"});
          const div = document.createElement("div");
          div.className="list-item";
          div.innerHTML =
            '<div><b>' + b.clubName + '</b> • ' + dt + '</div>' +
            '<div class="muted">' + (b.eventTitle || '') + '</div>' +
            '<div>Стол <b>#' + b.tableNumber + '</b> • Гостей: <b>' + b.guestsCount + '</b></div>' +
            '<div class="muted">Итого депозит: ' + fmtMoney(b.totalDeposit) + ' • Статус: ' + b.status + '</div>';
          list.appendChild(div);
        });
      }catch(e){}
    }

    window.addEventListener("DOMContentLoaded", async ()=>{
      if (state.tgUser){
        const fullName = ((state.tgUser.first_name||"") + " " + (state.tgUser.last_name||"")).trim();
        $("hello").textContent = fullName || (state.tgUser.username? "@"+state.tgUser.username : "");
        $("guestName").value = $("hello").textContent;
      }
      await loadClubs();
      await loadMyBookings();
    });
  </script>
</head>
<body>
  <div class="wrap">
    <div class="card">
      <h1>Бронирование стола</h1>
      <div class="muted">Мини‑приложение открыто. В Telegram WebApp ваши данные будут подставлены автоматически.</div>
      <div id="note" class=""></div>
    </div>

    <div class="card">
      <h2>1) Выбор клуба</h2>
      <div class="row">
        <select id="club" onchange="onClubChanged()"></select>
      </div>
      <div id="clubs-empty" class="muted" style="display:none;margin-top:8px">
        В базе нет клубов. Добавьте их в админке или миграцией.
      </div>
    </div>

    <div class="card">
      <h2>2) Дата / событие</h2>
      <div class="row">
        <select id="event" onchange="onEventChanged()"></select>
      </div>
      <div class="sep"></div>
      <div class="row">
        <label class="muted">Гостей:</label>
        <input id="guests" type="number" min="1" max="50" value="2" oninput="onGuestsChanged()" style="width:90px"/>
      </div>
    </div>

    <div class="card">
      <h2>3) Свободные столы</h2>
      <div id="tables" class="grid"></div>
      <div class="sep"></div>
      <div id="summary" class="muted">Выберите стол.</div>
    </div>

    <div id="confirm-wrap" class="card">
      <h2>4) Контакты и подтверждение</h2>
      <div class="row">
        <input id="guestName" type="text" placeholder="Имя" style="flex:1;min-width:180px"/>
        <input id="phone" type="tel" placeholder="+7..." style="flex:1;min-width:180px"/>
      </div>
      <div class="sep"></div>
      <div class="row">
        <button class="ghost" onclick="location.reload()">Сбросить</button>
        <button class="primary" onclick="submitBooking()">Подтвердить бронь</button>
      </div>
    </div>

    <div id="done-wrap" class="card" style="display:none">
      <h2>Бронь создана</h2>
      <div id="done-text"></div>
      <div class="sep"></div>
      <div class="row">
        <button class="primary" onclick="window.Telegram?.WebApp?.close()">Закрыть</button>
        <button class="ghost" onclick="location.reload()">Новая бронь</button>
      </div>
    </div>

    <div class="card">
      <h2>Мои бронирования</h2>
      <div id="my-list"></div>
      <div class="muted">Привязка идёт к вашему Telegram‑аккаунту.</div>
    </div>

    <div class="card">
      <div class="muted">Здравствуйте, <span id="hello"></span></div>
    </div>
  </div>
</body>
</html>
""".trimIndent()

private val json = Json { ignoreUnknownKeys = true }

/** Подключить все UI/REST маршруты мини‑приложения бронирования. */
fun Application.installBookingWebApp() {
    routing {
        get("/ui/checkin") { call.respondText(CHECKIN_HTML, ContentType.Text.Html) }

        // === REST: справочники ===
        get("/api/clubs") {
            val clubs: List<ClubDto> = transaction {
                Clubs
                    .selectAll()
                    .orderBy(Clubs.id, SortOrder.ASC)
                    .map { ClubDto(it[Clubs.id], it[Clubs.name], it[Clubs.description]) }
            }
            call.respondText(json.encodeToString(clubs), ContentType.Application.Json)
        }

        get("/api/events") {
            val clubId = call.request.queryParameters["clubId"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "clubId is required")

            val now = Instant.now()
            val events: List<EventDto> = transaction {
                Events
                    .selectAll()
                    .where { (Events.clubId eq clubId) and (Events.endAt greaterEq now) }
                    .orderBy(Events.startAt, SortOrder.ASC)
                    .limit(50)
                    .map {
                        EventDto(
                            id = it[Events.id],
                            clubId = it[Events.clubId],
                            title = it[Events.title],
                            startAt = it[Events.startAt].toString(),
                            endAt = it[Events.endAt].toString(),
                            isSpecial = it[Events.isSpecial]
                        )
                    }
            }
            call.respondText(json.encodeToString(events), ContentType.Application.Json)
        }

        get("/api/tables/free") {
            val clubId = call.request.queryParameters["clubId"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "clubId is required")
            val eventId = call.request.queryParameters["eventId"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "eventId is required")
            val guests = max(1, call.request.queryParameters["guests"]?.toIntOrNull() ?: 1)

            val free: List<TableDto> = transaction {
                val busyTableIds: Set<Long> = Bookings
                    .selectAll()
                    .where { (Bookings.eventId eq eventId) and (Bookings.status inList listOf("CONFIRMED", "SEATED")) }
                    .map { it[Bookings.tableId] }
                    .toSet()

                Tables
                    .selectAll()
                    .where {
                        (Tables.clubId eq clubId) and
                            (Tables.active eq true) and
                            (Tables.capacity greaterEq guests)
                    }
                    .orderBy(Tables.tableNumber, SortOrder.ASC)
                    .mapNotNull {
                        val id = it[Tables.id]
                        if (id in busyTableIds) null
                        else TableDto(
                            id = id,
                            clubId = it[Tables.clubId],
                            zoneId = it[Tables.zoneId],
                            tableNumber = it[Tables.tableNumber],
                            capacity = it[Tables.capacity],
                            minDeposit = it[Tables.minDeposit].toPlainString()
                        )
                    }
            }
            call.respondText(json.encodeToString(free), ContentType.Application.Json)
        }

        // === REST: бронирование ===
        post("/api/bookings") {
            val raw = call.receiveText()
            val req = try {
                json.decodeFromString<BookingRequest>(raw)
            } catch (_: Throwable) {
                return@post call.respond(HttpStatusCode.BadRequest, "invalid json")
            }

            val tgUserId = call.request.headers["X-TG-User-Id"]?.toLongOrNull()
            val tgUsername = call.request.headers["X-TG-Username"]
            val tgDisplay = call.request.headers["X-TG-Display"]
            if (tgUserId == null) {
                return@post call.respond(HttpStatusCode.BadRequest, "X-TG-User-Id header required")
            }

            val result: BookingResult = transaction {
                val event = Events
                    .selectAll()
                    .where { Events.id eq req.eventId }
                    .limit(1)
                    .firstOrNull() ?: return@transaction BookingError("EVENT_NOT_FOUND")

                if (event[Events.clubId] != req.clubId) {
                    return@transaction BookingError("EVENT_CLUB_MISMATCH")
                }

                val table = Tables
                    .selectAll()
                    .where { Tables.id eq req.tableId }
                    .limit(1)
                    .firstOrNull() ?: return@transaction BookingError("TABLE_NOT_FOUND")

                if (table[Tables.clubId] != req.clubId) return@transaction BookingError("TABLE_CLUB_MISMATCH")
                if (!table[Tables.active]) return@transaction BookingError("TABLE_INACTIVE")
                if (req.guestsCount <= 0 || req.guestsCount > table[Tables.capacity]) {
                    return@transaction BookingError("CAPACITY_EXCEEDED")
                }

                val existsActive = Bookings
                    .selectAll()
                    .where {
                        (Bookings.eventId eq req.eventId) and
                            (Bookings.tableId eq req.tableId) and
                            (Bookings.status inList listOf("CONFIRMED", "SEATED"))
                    }
                    .any()
                if (existsActive) return@transaction BookingError("ALREADY_BOOKED")

                val userId: Long = ensureUser(tgUserId, tgUsername, tgDisplay, req.phoneE164)
                val userIdNullable: Long? = userId

                val minDep: BigDecimal = table[Tables.minDeposit]
                val total: BigDecimal = minDep.multiply(BigDecimal(req.guestsCount))

                val qrCodeSecret = randomHex()   // по умолчанию 32 байта
                val idem = "tg-$tgUserId-${req.eventId}-${req.tableId}-${req.guestsCount}"

                try {
                    Bookings.insert {
                        it[eventId]        = req.eventId
                        it[clubId]         = req.clubId
                        it[tableId]        = req.tableId
                        it[tableNumber]    = table[Tables.tableNumber]
                        it[guestUserId]    = userIdNullable
                        it[guestName]      = req.guestName?.takeIf(String::isNotBlank) ?: tgDisplay ?: tgUsername
                        it[phoneE164]      = req.phoneE164
                        it[promoterUserId] = null
                        it[guestsCount]    = req.guestsCount
                        it[minDeposit]     = minDep
                        it[totalDeposit]   = total
                        it[arrivalBy]      = req.arrivalBy?.let(Instant::parse)
                        it[status]         = "CONFIRMED"
                        it[Bookings.qrSecret] = qrCodeSecret
                        it[idempotencyKey] = idem
                        it[createdAt]      = Instant.now()
                        it[updatedAt]      = Instant.now()
                    }
                } catch (_: Throwable) {
                    return@transaction BookingError("CONFLICT")
                }

                BookingOk(
                    BookingCreated(
                        clubId       = req.clubId,
                        eventId      = req.eventId,
                        tableId      = req.tableId,
                        tableNumber  = table[Tables.tableNumber],
                        guestsCount  = req.guestsCount,
                        minDeposit   = minDep.toPlainString(),
                        totalDeposit = total.toPlainString(),
                        qrSecret     = qrCodeSecret
                    )
                )
            }

            when (result) {
                is BookingOk   -> {
                    notifyHq(buildNotifyText(result.data, tgUserId, tgUsername, tgDisplay))
                    call.respondText(json.encodeToString(result.data), ContentType.Application.Json)
                }
                is BookingError -> call.respond(HttpStatusCode.Conflict, result.code)
            }
        }

        // «Мои бронирования» по telegram_user_id
        get("/api/bookings/my") {
            val tgUserId = call.request.queryParameters["tgUserId"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "tgUserId is required")

            val list: List<MyBookingDto> = transaction {
                val user = Users
                    .selectAll()
                    .where { Users.telegramUserId eq tgUserId }
                    .firstOrNull()
                    ?: return@transaction emptyList<MyBookingDto>()

                Bookings
                    .join(Events, INNER, additionalConstraint = { Bookings.eventId eq Events.id })
                    .join(Clubs,  INNER, additionalConstraint = { Bookings.clubId eq Clubs.id })
                    .selectAll()
                    .where { Bookings.guestUserId eq user[Users.id] }
                    .orderBy(Bookings.createdAt, SortOrder.DESC)
                    .limit(20)
                    .map {
                        MyBookingDto(
                            id           = it[Bookings.id].toString(),
                            clubName     = it[Clubs.name],
                            eventTitle   = it[Events.title],
                            eventStartAt = it[Events.startAt].toString(),
                            tableNumber  = it[Bookings.tableNumber],
                            guestsCount  = it[Bookings.guestsCount],
                            totalDeposit = it[Bookings.totalDeposit].toPlainString(),
                            status       = it[Bookings.status]
                        )
                    }
            }
            call.respondText(json.encodeToString(list), ContentType.Application.Json)
        }
    }
}

/* ==========================  Exposed таблицы ========================== */

private object Clubs : Table("clubs") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val description = text("description").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object Events : Table("events") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val title = text("title").nullable()
    val startAt = timestamptz("start_at")
    val endAt = timestamptz("end_at")
    val isSpecial = bool("is_special")
    override val primaryKey = PrimaryKey(id)
}

private object Tables : Table("tables") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val zoneId = long("zone_id").nullable()
    val tableNumber = integer("table_number")
    val capacity = integer("capacity")
    val minDeposit = decimal("min_deposit", 12, 2)
    val active = bool("active")
    override val primaryKey = PrimaryKey(id)
}

private object Users : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id").nullable()
    val username = text("username").nullable()
    val displayName = text("display_name").nullable()
    val phoneE164 = text("phone_e164").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object Bookings : Table("bookings") {
    val id = uuid("id")
    val eventId = long("event_id")
    val clubId = long("club_id")
    val tableId = long("table_id")
    val tableNumber = integer("table_number")
    val guestUserId = long("guest_user_id").nullable()
    val guestName = text("guest_name").nullable()
    val phoneE164 = text("phone_e164").nullable()
    val promoterUserId = long("promoter_user_id").nullable()
    val guestsCount = integer("guests_count")
    val minDeposit = decimal("min_deposit", 12, 2)
    val totalDeposit = decimal("total_deposit", 12, 2)
    val arrivalBy = timestamptz("arrival_by").nullable()
    val status = text("status")
    val qrSecret = varchar("qr_secret", 64)
    val idempotencyKey = text("idempotency_key")
    val createdAt = timestamptz("created_at")
    val updatedAt = timestamptz("updated_at")
}

/* ==========================  DTO ========================== */

@Serializable private data class ClubDto(val id: Long, val name: String, val description: String?)
@Serializable private data class EventDto(
    val id: Long, val clubId: Long, val title: String?, val startAt: String, val endAt: String, val isSpecial: Boolean
)
@Serializable private data class TableDto(
    val id: Long, val clubId: Long, val zoneId: Long?, val tableNumber: Int, val capacity: Int, val minDeposit: String
)
@Serializable private data class BookingRequest(
    val clubId: Long, val eventId: Long, val tableId: Long, val guestsCount: Int,
    val guestName: String? = null, val phoneE164: String? = null, val arrivalBy: String? = null
)
@Serializable private data class BookingCreated(
    val clubId: Long, val eventId: Long, val tableId: Long, val tableNumber: Int,
    val guestsCount: Int, val minDeposit: String, val totalDeposit: String, val qrSecret: String
)
@Serializable private data class MyBookingDto(
    val id: String, val clubName: String, val eventTitle: String?, val eventStartAt: String,
    val tableNumber: Int, val guestsCount: Int, val totalDeposit: String, val status: String
)

private sealed interface BookingResult
private data class BookingOk(val data: BookingCreated) : BookingResult
private data class BookingError(val code: String) : BookingResult

/* ==========================  Вспомогательные ========================== */

private fun ensureUser(
    tgUserId: Long, tgUsername: String?, tgDisplay: String?, phone: String?
): Long {
    val exists = Users.selectAll().where { Users.telegramUserId eq tgUserId }.firstOrNull()
    if (exists != null) return exists[Users.id]
    val stmt = Users.insert {
        it[telegramUserId] = tgUserId
        it[username] = tgUsername
        it[displayName] = tgDisplay ?: tgUsername
        it[phoneE164] = phone
    }
    return stmt[Users.id]
}

private fun randomHex(bytes: Int = 32): String {
    val buf = ByteArray(bytes)
    SecureRandom().nextBytes(buf)
    val sb = StringBuilder(bytes * 2)
    for (b in buf) sb.append(String.format("%02x", b))
    return sb.toString()
}

private fun notifyHq(textHtml: String, parseMode: String = "HTML") {
    val token = System.getenv("TELEGRAM_BOT_TOKEN") ?: return
    val chatId = System.getenv("HQ_CHAT_ID") ?: return
    try {
        val url = buildString {
            append("https://api.telegram.org/bot").append(token)
            append("/sendMessage?chat_id=").append(URLEncoder.encode(chatId, "UTF-8"))
            append("&parse_mode=").append(parseMode)
            append("&disable_web_page_preview=true&text=").append(URLEncoder.encode(textHtml, "UTF-8"))
        }
        // Избегаем устаревшего URL(String)
        val input = java.net.URI.create(url).toURL().openStream()
        input.use { it.readBytes() }
    } catch (_: Throwable) { /* swallow */ }
}

private fun buildNotifyText(b: BookingCreated, tgUserId: Long, user: String?, display: String?): String = """
<b>Новая бронь</b>
Стол: <b>#${b.tableNumber}</b>
Гостей: <b>${b.guestsCount}</b>
Мин. депозит: <b>${b.minDeposit}</b>
Итого депозит: <b>${b.totalDeposit}</b>

TG: <code>$tgUserId</code> ${if (!display.isNullOrBlank()) "($display)" else ""} ${if (!user.isNullOrBlank()) "@$user" else ""}
QR: <code>${b.qrSecret}</code>
""".trim()

/* ===== TIMESTAMPTZ support без exposed-java-time ===== */

private class InstantTzColumnType : ColumnType() {
    override fun sqlType(): String = "TIMESTAMP WITH TIME ZONE"
    override fun valueFromDB(value: Any): Any = when (value) {
        is Instant       -> value
        is Timestamp     -> value.toInstant()
        is ZonedDateTime -> value.toInstant()
        is String        -> Instant.parse(value)
        else -> error("Unexpected value for TIMESTAMPTZ: $value (${value::class})")
    }
    override fun notNullValueToDB(value: Any): Any =
        if (value is Instant) Timestamp.from(value) else value
}

private fun Table.timestamptz(name: String): Column<Instant> =
    registerColumn(name, InstantTzColumnType())

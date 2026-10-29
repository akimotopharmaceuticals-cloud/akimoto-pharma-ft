// Akimoto Pharma FT server: receives data from the MR app and serves the manager dashboard.
// No external packages needed. Run: API_KEY=yourkey node server.js
"use strict";
const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.env.PORT) || 8080;
const API_KEY = process.env.API_KEY || "akimoto-demo-key";
const DATA_FILE = process.env.DATA_FILE || path.join(__dirname, "data", "fieldtrack.json");
const IST_OFFSET = "+05:30";
const GEOFENCE_M = 150, MAX_ACC_M = 50;
// Company expense rules (₹), sent to every phone on sync.
const RULES = {
  da_hq: Number(process.env.DA_HQ) || 250,
  da_exhq: Number(process.env.DA_EXHQ) || 400,
  da_out: Number(process.env.DA_OUT) || 800,
  ta_per_km: Number(process.env.TA_PER_KM) || 3.5,
  // open work days close automatically at this hour (24 h, phone's local time)
  day_end_hour: Math.min(23, Math.max(12, Number(process.env.DAY_END_HOUR) || 21))
};
const BILL_DIR = path.join(path.dirname(DATA_FILE), "bills");

// ---------- storage (one JSON file; fine for a pilot of up to ~100 MRs) ----------
let db = { mrs: {}, pings: {}, visits: {}, days: {}, tours: {}, expenses: {} };
try { db = Object.assign(db, JSON.parse(fs.readFileSync(DATA_FILE, "utf8"))); } catch (e) { /* first run */ }
let saveTimer = null;
function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    fs.mkdirSync(path.dirname(DATA_FILE), { recursive: true });
    const tmp = DATA_FILE + ".tmp";
    fs.writeFileSync(tmp, JSON.stringify(db));
    fs.renameSync(tmp, DATA_FILE);
  }, 500);
}

// ---------- helpers ----------
function distM(a, b) {
  const R = 6371000, toR = x => x * Math.PI / 180;
  const dLat = toR(b.lat - a.lat), dLng = toR(b.lng - a.lng);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(toR(a.lat)) * Math.cos(toR(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
function kmOf(pings) {
  let total = 0, last = null;
  for (const p of [...pings].sort((x, y) => x.ts - y.ts)) {
    if (p.acc > MAX_ACC_M || p.mock) continue;
    if (!last) { last = p; continue; }
    const d = distM(last, p);
    if (d < 20) continue;
    const hrs = (p.ts - last.ts) / 3.6e6;
    if (hrs > 0 && d / 1000 / hrs > 150) continue;
    total += d; last = p;
  }
  return total / 1000;
}
function istDayRange(dateStr) {
  const start = Date.parse(`${dateStr}T00:00:00${IST_OFFSET}`);
  return [start, start + 86400000];
}
function todayIST() {
  return new Date(Date.now() + 5.5 * 3600000).toISOString().slice(0, 10);
}
function send(res, code, body, type = "application/json") {
  res.writeHead(code, { "Content-Type": type, "Cache-Control": "no-store" });
  res.end(type === "application/json" ? JSON.stringify(body) : body);
}
function authed(req) {
  return (req.headers["x-api-key"] || "") === API_KEY;
}
function readBody(req, limit = 8 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on("data", c => { size += c.length; if (size > limit) { reject(new Error("too large")); req.destroy(); } else chunks.push(c); });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}
const num = v => (typeof v === "number" && isFinite(v) ? v : null);
const str = (v, n = 500) => (typeof v === "string" ? v.slice(0, n) : "");

// ---------- sync from phones ----------
function ingest(body) {
  const code = str(body.mr && body.mr.code, 40).trim().toUpperCase();
  const device = str(body.device && body.device.id, 40);
  if (!code || !device) throw new Error("mr.code and device.id are required");
  const mr = db.mrs[code] || { code, firstSeen: Date.now() };
  mr.name = str(body.mr.name, 80) || mr.name || code;
  mr.device = device;
  mr.model = str(body.device.model, 80);
  mr.lastSync = Date.now();
  db.mrs[code] = mr;
  let n = { pings: 0, visits: 0, days: 0 };
  n.code = code; n.device = device;
  for (const p of body.pings || []) {
    if (num(p.lat) === null || num(p.lng) === null || num(p.ts) === null) continue;
    db.pings[`${device}:${p.id}`] = { mr: code, day: `${device}:${p.day_id}`, ts: p.ts, lat: p.lat, lng: p.lng,
      acc: num(p.acc) ?? 999, speed: num(p.speed) ?? 0, battery: num(p.battery), mock: !!p.mock };
    n.pings++;
  }
  for (const v of body.visits || []) {
    if (num(v.lat) === null || num(v.ts) === null) continue;
    db.visits[`${device}:${v.id}`] = { mr: code, day: `${device}:${v.day_id}`, ts: v.ts, lat: v.lat, lng: v.lng,
      acc: num(v.acc) ?? 999, dist_m: num(v.dist_m) ?? -1, doctor: str(v.doctor, 120), speciality: str(v.speciality, 80),
      area: str(v.area, 80), products: str(v.products), samples: str(v.samples), rx: num(v.rx) ?? 0,
      feedback: str(v.feedback, 1000), mock: !!v.mock, verified: !!v.verified };
    n.visits++;
  }
  for (const d of body.days || []) {
    if (num(d.start_ts) === null) continue;
    const key = `${device}:${d.id}`;
    const prev = db.days[key];
    let selfie = prev ? prev.selfie : null;
    if (typeof d.selfie_jpg === "string" && d.selfie_jpg.length < 1_500_000) {
      fs.mkdirSync(BILL_DIR, { recursive: true });
      selfie = `selfie_${device}_${d.id}.jpg`.replace(/[^\w.-]/g, "");
      fs.writeFileSync(path.join(BILL_DIR, selfie), Buffer.from(d.selfie_jpg, "base64"));
    }
    db.days[key] = { mr: code, start_ts: d.start_ts, start_lat: num(d.start_lat), start_lng: num(d.start_lng),
      end_ts: num(d.end_ts), km: num(d.km), auto_ended: !!d.auto_ended, selfie,
      selfie_skipped: !selfie && !!d.selfie_skipped };
    n.days++;
  }
  for (const t of body.tour_plans || []) {
    if (!/^\d{4}-\d{2}$/.test(t.month || "")) continue;
    const days = {};
    for (const [d, a] of Object.entries(t.days || {})) if (+d >= 1 && +d <= 31) days[+d] = str(a, 80);
    db.tours[`${code}:${t.month}`] = { mr: code, month: t.month, days, status: "Pending", note: "",
      submitted_ts: num(t.submitted_ts) || Date.now(), decided_ts: null };
    n.tours = (n.tours || 0) + 1;
  }
  for (const e of body.expenses || []) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(e.date || "")) continue;
    const key = `${device}:${e.id}`;
    const prev = db.expenses[key];
    let bill = prev ? prev.bill : null;
    if (typeof e.bill_jpg === "string" && e.bill_jpg.length < 3_000_000) {
      fs.mkdirSync(BILL_DIR, { recursive: true });
      bill = `${device}_${e.id}.jpg`.replace(/[^\w.-]/g, "");
      fs.writeFileSync(path.join(BILL_DIR, bill), Buffer.from(e.bill_jpg, "base64"));
    }
    // A new claim for the same day replaces an earlier returned one.
    for (const [k, x] of Object.entries(db.expenses))
      if (k !== key && x.mr === code && x.date === e.date && x.status === "Returned") x.status = "Replaced";
    db.expenses[key] = { key, mr: code, device, id: e.id, date: e.date, da_type: str(e.da_type, 20), da: num(e.da) ?? 0,
      km: num(e.km) ?? 0, rate: num(e.rate) ?? 0, ta: num(e.ta) ?? 0, other: num(e.other) ?? 0,
      other_note: str(e.other_note, 200), total: num(e.total) ?? 0, bill,
      status: prev && prev.status !== "Pending" ? prev.status : "Pending", note: prev ? prev.note : "",
      created_ts: prev ? prev.created_ts : Date.now() };
    n.expenses = (n.expenses || 0) + 1;
  }
  save();
  return n;
}

// What goes back to the phone: rates and the manager's decisions for this MR / device.
function replyFor(code, device) {
  const tour = {}, expenses = {};
  for (const t of Object.values(db.tours)) if (t.mr === code) tour[t.month] = { status: t.status, note: t.note };
  for (const e of Object.values(db.expenses)) if (e.device === device && e.status !== "Replaced") expenses[e.id] = { status: e.status, note: e.note };
  return { rules: RULES, tour, expenses };
}

function gpsKmFor(code, date) {
  const [from, to] = istDayRange(date);
  return kmOf(Object.values(db.pings).filter(p => p.mr === code && p.ts >= from && p.ts < to));
}

function approvals() {
  const name = c => (db.mrs[c] && db.mrs[c].name) || c;
  const tours = Object.entries(db.tours).map(([key, t]) => ({ key, ...t, name: name(t.mr) }))
    .sort((a, b) => b.submitted_ts - a.submitted_ts);
  const expenses = Object.values(db.expenses).filter(e => e.status !== "Replaced").map(e => {
    const gps = Math.round(gpsKmFor(e.mr, e.date) * 10) / 10;
    const flags = [];
    if (e.km > gps * 1.1 + 1) flags.push(`Claimed ${e.km.toFixed(1)} km but server GPS shows ${gps} km`);
    if (e.other > 0 && !e.bill) flags.push("Other expense without a bill photo");
    return { ...e, name: name(e.mr), gps_km: gps, flags };
  }).sort((a, b) => b.date.localeCompare(a.date) || a.name.localeCompare(b.name));
  return { rules: RULES, tours, expenses };
}

function decide(body) {
  const status = body.status === "Approved" ? "Approved" : body.status === "Returned" ? "Returned" : null;
  if (!status) throw new Error("status must be Approved or Returned");
  const table = body.kind === "tour" ? db.tours : body.kind === "expense" ? db.expenses : null;
  if (!table || !table[body.key]) throw new Error("item not found");
  if (status === "Returned" && !str(body.note).trim()) throw new Error("Add a note saying what to change");
  Object.assign(table[body.key], { status, note: str(body.note, 300), decided_ts: Date.now() });
  save();
  return table[body.key];
}

function expensesCsv(month) {
  const q = v => { v = String(v ?? ""); return /[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v; };
  const rows = approvals().expenses.filter(e => e.date.startsWith(month));
  let out = "mr_code,mr_name,date,da_type,da,km_claimed,km_gps_server,rate_per_km,ta,other,other_note,total,status,manager_note,bill\n";
  for (const e of rows) out += [e.mr, e.name, e.date, e.da_type, e.da, e.km.toFixed(1), e.gps_km, e.rate, e.ta.toFixed(0),
    e.other, e.other_note, e.total.toFixed(0), e.status, e.note, e.bill ? "yes" : "no"].map(q).join(",") + "\n";
  out += "\nmr_code,mr_name,approved_total,pending_total\n";
  const by = {};
  for (const e of rows) { const b = by[e.mr] || (by[e.mr] = { name: e.name, a: 0, p: 0 }); if (e.status === "Approved") b.a += e.total; if (e.status === "Pending") b.p += e.total; }
  for (const [c, b] of Object.entries(by)) out += [c, b.name, b.a.toFixed(0), b.p.toFixed(0)].map(q).join(",") + "\n";
  return out;
}

// ---------- dashboard state for one IST date ----------
function state(dateStr) {
  const [from, to] = istDayRange(dateStr);
  const now = Date.now();
  const byMr = {};
  for (const code of Object.keys(db.mrs)) byMr[code] = { ...db.mrs[code], pings: [], visits: [], days: [] };
  for (const p of Object.values(db.pings)) if (p.ts >= from && p.ts < to && byMr[p.mr]) byMr[p.mr].pings.push(p);
  for (const v of Object.values(db.visits)) if (v.ts >= from && v.ts < to && byMr[v.mr]) byMr[v.mr].visits.push(v);
  for (const d of Object.values(db.days)) if (d.start_ts >= from && d.start_ts < to && byMr[d.mr]) byMr[d.mr].days.push(d);

  const mrs = Object.values(byMr).map(m => {
    m.pings.sort((a, b) => a.ts - b.ts);
    m.visits.sort((a, b) => a.ts - b.ts);
    const last = m.pings[m.pings.length - 1] || null;
    const day = m.days.sort((a, b) => b.start_ts - a.start_ts)[0] || null;
    const open = !!day && !day.end_ts;
    const minsSince = last ? (now - last.ts) / 60000 : null;
    const flags = [];
    const mockPts = m.pings.filter(p => p.mock).length + m.visits.filter(v => v.mock).length;
    if (mockPts) flags.push({ sev: "bad", text: `Fake-GPS app detected on ${mockPts} records` });
    for (const v of m.visits) {
      if (!v.mock && v.dist_m > GEOFENCE_M) flags.push({ sev: "bad", text: `${v.doctor}: check-in ${Math.round(v.dist_m)} m from clinic` });
    }
    if (open && minsSince !== null && minsSince > 30 && dateStr === todayIST())
      flags.push({ sev: "warn", text: `No location for ${Math.round(minsSince)} min (phone off, no network or app stopped)` });
    // stationary: last 90 minutes of points all within 150 m
    const recent = m.pings.filter(p => last && last.ts - p.ts <= 90 * 60000);
    if (open && recent.length >= 6 && recent.every(p => distM(p, last) < 150) && !m.visits.some(v => last.ts - v.ts < 90 * 60000))
      flags.push({ sev: "warn", text: "Same place for 90 min with no call reported" });
    if (day && day.selfie_skipped) flags.push({ sev: "warn", text: "Day started without a selfie" });
    if (day && day.auto_ended) flags.push({ sev: "warn", text: `Forgot to end the day; closed automatically at ${RULES.day_end_hour}:00` });
    let status = "notstarted";
    if (day && day.end_ts) status = "ended";
    else if (open || m.pings.length) status = minsSince !== null && minsSince > 30 ? "silent" : "active";
    return {
      code: m.code, name: m.name, model: m.model, lastSync: m.lastSync, status,
      dayStart: day ? day.start_ts : (m.pings[0] ? m.pings[0].ts : null), dayEnd: day ? day.end_ts : null,
      selfie: day ? day.selfie : null, autoEnded: day ? !!day.auto_ended : false,
      km: Math.round(kmOf(m.pings) * 10) / 10, last, battery: last ? last.battery : null,
      calls: m.visits.length, verified: m.visits.filter(v => v.verified).length,
      route: m.pings.filter(p => p.acc <= MAX_ACC_M && !p.mock).map(p => [p.lat, p.lng, p.ts]),
      visits: m.visits, flags
    };
  }).sort((a, b) => a.name.localeCompare(b.name));
  return { date: dateStr, generated: now, mrs };
}

function csvExport(dateStr) {
  const s = state(dateStr);
  const q = v => { v = String(v ?? ""); return /[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v; };
  const t = ts => new Date(ts + 5.5 * 3600000).toISOString().replace("T", " ").slice(0, 19);
  let out = "mr_code,mr_name,time_ist,doctor,speciality,area,products,samples,rx_per_week,feedback,lat,lng,accuracy_m,distance_from_clinic_m,status\n";
  for (const m of s.mrs) for (const v of m.visits) {
    const st = v.mock ? "fake GPS" : v.dist_m < 0 ? "first visit (pinned)" : v.verified ? "verified" : "flagged";
    out += [m.code, m.name, t(v.ts), v.doctor, v.speciality, v.area, v.products, v.samples, v.rx, v.feedback,
      v.lat, v.lng, Math.round(v.acc), v.dist_m < 0 ? "" : Math.round(v.dist_m), st].map(q).join(",") + "\n";
  }
  out += "\nmr_code,mr_name,day_start_ist,day_end_ist,km_gps,calls,verified_calls\n";
  for (const m of s.mrs) out += [m.code, m.name, m.dayStart ? t(m.dayStart) : "", m.dayEnd ? t(m.dayEnd) : "", m.km, m.calls, m.verified].map(q).join(",") + "\n";
  return out;
}

// ---------- http ----------
const dashboard = fs.readFileSync(path.join(__dirname, "dashboard.html"), "utf8");
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://x");
  try {
    if (req.method === "GET" && (url.pathname === "/" || url.pathname === "/index.html"))
      return send(res, 200, dashboard, "text/html; charset=utf-8");
    if (req.method === "GET" && url.pathname === "/health") return send(res, 200, { ok: true });
    if (url.pathname.startsWith("/api/") && !authed(req)) return send(res, 401, { error: "wrong access key" });
    if (req.method === "POST" && url.pathname === "/api/sync") {
      const n = ingest(JSON.parse(await readBody(req)));
      const reply = replyFor(n.code, n.device);
      delete n.code; delete n.device;
      return send(res, 200, { ok: true, received: n, ...reply });
    }
    if (req.method === "GET" && url.pathname === "/api/approvals") return send(res, 200, approvals());
    if (req.method === "POST" && url.pathname === "/api/decide")
      return send(res, 200, { ok: true, item: decide(JSON.parse(await readBody(req, 100000))) });
    if (req.method === "GET" && url.pathname === "/api/bill") {
      const f = String(url.searchParams.get("file") || "").replace(/[^\w.-]/g, "");
      const fp = path.join(BILL_DIR, f);
      if (!f || !fs.existsSync(fp)) return send(res, 404, { error: "no bill" });
      res.writeHead(200, { "Content-Type": "image/jpeg", "Cache-Control": "no-store" });
      return res.end(fs.readFileSync(fp));
    }
    if (req.method === "GET" && url.pathname === "/api/expenses.csv") {
      const m = /^\d{4}-\d{2}$/.test(url.searchParams.get("month") || "") ? url.searchParams.get("month") : todayIST().slice(0, 7);
      return send(res, 200, expensesCsv(m), "text/csv; charset=utf-8");
    }
    const date = /^\d{4}-\d{2}-\d{2}$/.test(url.searchParams.get("date") || "") ? url.searchParams.get("date") : todayIST();
    if (req.method === "GET" && url.pathname === "/api/state") return send(res, 200, state(date));
    if (req.method === "GET" && url.pathname === "/api/export.csv")
      return send(res, 200, csvExport(date), "text/csv; charset=utf-8");
    send(res, 404, { error: "not found" });
  } catch (e) {
    send(res, 400, { error: e.message });
  }
});
server.listen(PORT, () => console.log(`Akimoto Pharma FT server on :${PORT} (data: ${DATA_FILE})`));

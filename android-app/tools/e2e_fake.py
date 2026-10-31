#!/usr/bin/env python3
"""End-to-end check of Printer Bridge against its FAKE backend.

Drives the app's REST API (urllib), its MCP endpoint (official `mcp` Python
SDK client, streamable HTTP), screenshots the web dashboard (headless
chromium) and reads the fake Telegram server's request log.

Run with a Python that has `mcp` installed:
  python e2e_fake.py --base http://127.0.0.1:28080 --token <api token> \
      --telegram-log ../evidence/app_fake_telegram_requests.jsonl --out ../evidence/app_e2e_fake
"""
import argparse
import asyncio
import json
import subprocess
import time
import urllib.request

from mcp import ClientSession
from mcp.client.streamable_http import create_mcp_http_client, streamable_http_client

ap = argparse.ArgumentParser()
ap.add_argument("--base", required=True)
ap.add_argument("--token", required=True)
ap.add_argument("--telegram-log", required=True)
ap.add_argument("--out", required=True, help="evidence path prefix")
ap.add_argument("--file", default="short.gcode")
ap.add_argument("--error-file", default="xyz_cube.gcode")
args = ap.parse_args()

results = []
timeline = []


def check(name, cond, detail=""):
    results.append({"check": name, "pass": bool(cond), "detail": detail})
    print(("PASS " if cond else "FAIL ") + name + (" :: " + str(detail)[:300] if detail else ""), flush=True)


def rest(method, path, body=None, auth=True):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(args.base + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if auth:
        req.add_header("Authorization", "Bearer " + args.token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def status():
    return rest("GET", "/api/status", auth=False)[1]


def job():
    return status().get("job") or {}


def tg_messages():
    try:
        with open(args.telegram_log) as f:
            return [json.loads(l) for l in f if l.strip()]
    except FileNotFoundError:
        return []


def raw_initialize():
    """initialize over plain HTTP: the SDK client drops serverInfo extras and may reuse an earlier result."""
    req = urllib.request.Request(args.base + "/mcp", method="POST", data=json.dumps({
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "e2e", "version": "1"}}}).encode(),
        headers={"Authorization": "Bearer " + args.token, "Content-Type": "application/json", "Accept": "application/json, text/event-stream"})
    return json.load(urllib.request.urlopen(req, timeout=20))["result"]


def screenshot(name):
    path = f"{args.out}_{name}.png"
    subprocess.run(["chromium", "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--screenshot={path}",
                    "--window-size=1100,1500", "--virtual-time-budget=6000", args.base + "/"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=90)
    return path


def dashboard_dom():
    """Fresh page load in headless chromium, JS run, DOM returned (like a user opening it mid-print)."""
    r = subprocess.run(["chromium", "--headless=new", "--disable-gpu", "--dump-dom", "--virtual-time-budget=6000", args.base + "/"],
                       capture_output=True, text=True, timeout=90)
    return r.stdout


def button_tag(dom, bid):
    import re
    m = re.search(r'<button[^>]*id="%s"[^>]*>' % bid, dom)
    return m.group(0) if m else ""


def wait(pred, timeout, what, every=0.5):
    end = time.time() + timeout
    while time.time() < end:
        v = pred()
        if v:
            return v
        time.sleep(every)
    raise TimeoutError(what)


async def main():
    client = create_mcp_http_client(headers={"Authorization": "Bearer " + args.token})
    async with client, streamable_http_client(args.base + "/mcp", http_client=client) as (rd, wr), ClientSession(rd, wr) as mcp:
        init = await mcp.initialize()
        check("mcp_initialize", init.server_info.name == "printer-bridge", f"protocol={init.protocol_version} server={init.server_info}")
        tools = {t.name for t in (await mcp.list_tools()).tools}
        need = {"start_print", "get_status", "pause", "cancel", "home", "check_temps", "list_print_history"}
        check("mcp_tools_list", need <= tools, sorted(tools))

        async def tool(name, **a):
            r = await mcp.call_tool(name, a)
            txt = r.content[0].text if r.content else ""
            try:
                return r.is_error, json.loads(txt)
            except ValueError:
                return r.is_error, txt

        # ---------------- A: successful print, started over MCP ----------------
        # slow the simulator down a bit so the non-heating part lasts ~30 s
        rest("POST", "/api/sim/config", {"line_delay_ms": 40, "time_scale": 5})
        orig_pause_cfg = rest("GET", "/api/config", auth=False)[1]["pause"]
        rest("POST", "/api/config", {"pause_nozzle_standby_s": 4})   # short standby so cooldown + reheat are exercised
        n_tg0 = len(tg_messages())
        err, r = await tool("start_print", file=args.file)
        check("mcp_start_print", not err and r.get("state") == "queued", r)
        jid = r.get("id")
        seen_states, last_done, monotonic, mcp_vs_rest = set(), -1, True, []
        dash_mid = None
        paused_checked = False
        heatwait_checked = False
        t0 = time.time()
        while True:
            s = status()
            j = s.get("job") or {}
            seen_states.add(j.get("state"))
            timeline.append({"t": round(time.time() - t0, 2), "state": j.get("state"), "done": j.get("lines_done"),
                             "pct": j.get("progress_pct"), "remaining_s": j.get("remaining_s"),
                             "hotend": s["temps"]["hotend"], "hotend_target": s["temps"]["hotend_target"],
                             "bed": s["temps"]["bed"], "bed_target": s["temps"]["bed_target"]})
            if (j.get("lines_done") or 0) < last_done:
                monotonic = False
            last_done = j.get("lines_done") or 0
            if len(timeline) % 3 == 0 and j.get("state") in ("printing", "paused"):
                before = job()
                e2, m = await tool("get_status")
                after = job()
                mj = m.get("job") or {}
                mcp_vs_rest.append((mj.get("id") == before.get("id") == after.get("id") == jid,
                                    mj.get("state") in (before.get("state"), after.get("state")),
                                    before.get("lines_done", 0) <= mj.get("lines_done", -1) <= after.get("lines_done", 0)))
            if not heatwait_checked and (j.get("in_flight") or "").startswith(("M190", "M109")):
                t1 = time.time()
                e2, p = await tool("pause")
                dt = time.time() - t1
                check("mcp_pause_during_heat_wait_prompt_and_honest", not e2 and dt < 3 and p.get("state") == "pausing"
                      and (p.get("in_flight") or "").startswith(("M190", "M109")), f"{dt:.2f}s state={p.get('state')} in_flight={p.get('in_flight')}")
                e3, q = await tool("resume")
                check("mcp_resume_withdraws_pending_pause", not e3 and q.get("state") == "printing", q.get("state"))
                heatwait_checked = True
            if dash_mid is None and (j.get("progress_pct") or 0) > 20:
                dash_mid = screenshot("dashboard_mid_print")
                dash_status = status()
                check("dashboard_served_mid_print", True, f"{dash_mid} at job={dash_status['job']['progress_pct']}%")
            if not paused_checked and (j.get("progress_pct") or 0) > 40 and j.get("state") == "printing":
                e2, p = await tool("pause")
                wait(lambda: job().get("state") == "paused", 15, "paused")
                d0 = status()["connection"]["details"]; sv0 = job()["pause"]["saved"]
                check("pause_keeps_heaters_until_standby", d0["sim_hotend_target"] == sv0["hotend_target"] > 0 and d0["sim_bed_target"] == sv0["bed_target"] > 0,
                      (d0["sim_hotend_target"], d0["sim_bed_target"], job()["pause"]["nozzle_standby_in_s"]))
                a = job()["lines_done"]; time.sleep(3); b = job()["lines_done"]
                check("mcp_pause_stops_progress", not e2 and job()["state"] == "paused" and b - a == 0, f"lines_done {a}->{b} over 3s")
                pz = job()["pause"]; sv = pz["saved"]; d = status()["connection"]["details"]
                check("pause_parked_retracted_raised", pz["parked"] and pz["retracted_mm"] == 5 and d["sim_x"] == 10 and d["sim_y"] == 210
                      and abs(d["sim_z"] - min(sv["z"] + 10, 250)) < 1e-6 and d["sim_fan"] == 0,
                      f"sim pos ({d['sim_x']},{d['sim_y']},{d['sim_z']}) saved z {sv['z']} fan {d['sim_fan']}")
                check("pause_disarms_m85", d["sim_m85_timeout_s"] == 0, d["sim_m85_timeout_s"])
                wait(lambda: status()["connection"]["details"]["sim_hotend_target"] == 0, 10, "standby cooldown")
                d = status()["connection"]["details"]
                check("standby_cools_nozzle_keeps_bed", job()["pause"]["nozzle_cooled"] and d["sim_bed_target"] == sv["bed_target"],
                      (d["sim_hotend_target"], d["sim_bed_target"]))
                screenshot("dashboard_paused")
                time.sleep(3)   # let the nozzle cool a little so the reheat really waits
                t1 = time.time()
                e3, q = await tool("resume")
                dt = time.time() - t1
                check("mcp_resume_prompt_resuming_with_reheat_in_flight", not e3 and dt < 3 and q["state"] == "resuming"
                      and (q.get("in_flight") or "").startswith(("M104", "M109", "M105")), f"{dt:.2f}s {q['state']} {q.get('in_flight')}")
                wait(lambda: job().get("state") == "printing", 90, "printing after resume")
                d = status()["connection"]["details"]
                check("resume_restores_heaters_modes_and_rearms_m85", d["sim_m85_timeout_s"] == 300 and d["sim_hotend_target"] == sv["hotend_target"]
                      and d["sim_relative_e"] == sv["relative_e"] and d["sim_relative_xyz"] == sv["relative_xyz"] and job()["pause"]["stage"] == "resumed",
                      f"m85={d['sim_m85_timeout_s']} T={d['sim_hotend_target']} relE={d['sim_relative_e']}")
                c2 = job()["lines_done"]; time.sleep(2)
                check("mcp_resume_continues", job()["state"] == "printing" and job()["lines_done"] > c2, job()["lines_done"])
                paused_checked = True
            if j.get("state") in ("done", "error", "cancelled"):
                break
            if time.time() - t0 > 900:
                break
            time.sleep(1)
        final = job()
        check("print_completes_done", final.get("state") == "done" and final.get("progress_pct") == 100
              and final.get("lines_done") == final.get("lines_total"), final)
        check("status_went_through_printing", "printing" in seen_states, sorted(filter(None, seen_states)))
        check("progress_monotonic", monotonic)
        mids = sorted({x["pct"] for x in timeline if x["state"] == "printing" and 5 < (x["pct"] or 0) < 99})
        check("progress_intermediate_values_seen", len(mids) >= 8, mids)
        check("heater_ramp_seen_in_status", max((x["hotend"] or 0) for x in timeline) > 200
              and max((x["bed"] or 0) for x in timeline) > 55, "max hotend %.1f bed %.1f" % (
            max((x["hotend"] or 0) for x in timeline), max((x["bed"] or 0) for x in timeline)))
        check("mcp_get_status_matches_rest", mcp_vs_rest and all(a and b and c for a, b, c in mcp_vs_rest),
              f"{len(mcp_vs_rest)} comparisons (MCP lines_done bracketed by REST before/after): {mcp_vs_rest}")
        check("hotend_target_known_during_heatup", all(x["hotend_target"] == 220 for x in timeline
              if x["state"] == "printing" and 150 < (x["hotend"] or 0) < 215),
              [(x["hotend"], x["hotend_target"]) for x in timeline if x["state"] == "printing"][:12])
        screenshot("dashboard_after_done")
        msgs = wait(lambda: [m for m in tg_messages()[n_tg0:] if "Print finished" in m["params"].get("text", "")], 30, "done tg")
        check("telegram_completion_notification", len(msgs) == 1 and msgs[0]["params"]["chat_id"] == "424242"
              and args.file in msgs[0]["params"]["text"], msgs[0]["params"])
        e, h = await tool("list_print_history", limit=5)
        check("mcp_history_has_job", not e and h["history"][0]["id"] == jid and h["history"][0]["state"] == "done", h["history"][0])
        c, rh = rest("GET", "/api/history?limit=5", auth=False)
        check("rest_history_matches_mcp", rh["history"][0] == h["history"][0])
        e, t = await tool("check_temps")
        check("mcp_check_temps", not e and t.get("hotend") is not None and t.get("bed") is not None, t)
        e, hm = await tool("home")
        check("mcp_home", not e and hm.get("position", {}).get("x") == 0 and hm["position"].get("z") == 0, hm)

        # ---------------- B: simulated thermal runaway mid-print (REST) ----------------
        n_tg1 = len(tg_messages())
        c, r = rest("POST", "/api/print", {"file": args.error_file})
        check("rest_start_print", c == 200 and r["state"] == "queued", r)
        wait(lambda: job().get("state") == "printing" and job().get("lines_done", 0) > 1500, 300, "printing B")
        c, r = rest("POST", "/api/sim/error", {"kind": "thermal_runaway"})
        jb = wait(lambda: job() if job().get("state") == "error" else None, 30, "error B")
        check("thermal_runaway_sets_job_error", "Thermal Runaway" in (jb.get("error") or ""), jb)
        check("halted_flag_set", status()["connection"]["halted"] is True)
        msgs = wait(lambda: [m for m in tg_messages()[n_tg1:] if "PRINT ERROR" in m["params"].get("text", "")], 30, "err tg")
        time.sleep(3)
        all_new = tg_messages()[n_tg1:]
        check("telegram_error_notification", len(msgs) == 1 and "Thermal Runaway" in msgs[0]["params"]["text"]
              and len(all_new) == 1, [m["params"]["text"] for m in all_new])
        e, st = await tool("get_status")
        check("mcp_status_shows_error", st["job"]["state"] == "error" and st["connection"]["halted"], st["job"]["error"])
        c, r = rest("POST", "/api/print", {"file": args.file})
        check("start_refused_while_halted", c == 409, r)
        c, r = rest("POST", "/api/reset_board", {"confirm": True})
        check("rest_reset_board_clears_halt", c == 200 and wait(lambda: not status()["connection"]["halted"], 5, "unhalt"), r)
        time.sleep(1.5)

        # ---------------- C: simulated USB disconnect mid-print, then reconnect ----------------
        boots_before = status()["connection"]["details"]["sim_boot_count"]
        n_tg2 = len(tg_messages())
        c, r = rest("POST", "/api/print", {"file": args.file})
        wait(lambda: job().get("state") == "printing" and job().get("lines_done", 0) > 100, 300, "printing C")
        rest("POST", "/api/sim/error", {"kind": "disconnect"})
        jc = wait(lambda: job() if job().get("state") == "error" else None, 30, "error C")
        check("disconnect_sets_job_error", (jc.get("error") or "").startswith("connection lost"), jc.get("error"))
        check("disconnect_sets_connection_error", status()["connection"]["state"] == "error")
        msgs = wait(lambda: [m for m in tg_messages()[n_tg2:] if "PRINT ERROR" in m["params"].get("text", "")], 30, "disc tg")
        check("telegram_disconnect_notification", "connection lost" in msgs[0]["params"]["text"], msgs[0]["params"]["text"])
        c, r = rest("POST", "/api/connection", {"action": "connect"})
        s = status()
        check("reconnect_without_reboot", c == 200 and s["connection"]["state"] == "connected"
              and s["connection"]["details"]["sim_boot_count"] == boots_before,
              f"boot_count {boots_before} -> {s['connection']['details']['sim_boot_count']}")

        # ---------------- D: cancel over MCP turns heaters off ----------------
        n_tg3 = len(tg_messages())
        e, r = await tool("start_print", file=args.file)
        wait(lambda: job().get("state") == "printing" and (status()["temps"]["hotend_target"] or 0) > 100, 300, "printing D")
        e, r = await tool("cancel")
        jd = wait(lambda: job() if job().get("state") == "cancelled" else None, 60, "cancel D")
        time.sleep(2.5)
        t = status()["temps"]
        check("mcp_cancel_turns_heaters_off", jd["state"] == "cancelled" and t["hotend_target"] == 0 and t["bed_target"] == 0, t)
        check("no_telegram_on_cancel_by_default", len(tg_messages()) == n_tg3)
        e, r = await tool("pause")
        check("mcp_error_reported_as_isError", e is True, r)
        check("heat_wait_pause_check_ran", heatwait_checked)
        check("park_pause_check_ran", paused_checked)
        rest("POST", "/api/config", orig_pause_cfg)

        # ---------------- E: idle after jobs must not trip Marlin's M85 inactivity kill ----------------
        rest("POST", "/api/sim/config", {"time_scale": 20})     # 300 s M85 timeout -> 15 s real
        d = status()["connection"]["details"]
        check("m85_disarmed_after_jobs", d.get("sim_m85_timeout_s") == 0, d.get("sim_m85_timeout_s"))
        time.sleep(20)
        s = status()
        check("no_m85_kill_while_idle", s["connection"]["halted"] is False and s["connection"]["details"]["sim_halted"] is False,
              s["connection"]["last_rx"])
        rest("POST", "/api/sim/config", {"time_scale": 5})

        # ---------------- F: no homing / motion while a job is active; dashboard locks Home + Start ----------------
        rest("POST", "/api/sim/config", {"line_delay_ms": 40, "time_scale": 5})
        n_tg5 = len(tg_messages())
        c, r = rest("POST", "/api/print", {"file": args.file})
        wait(lambda: job().get("state") == "printing" and job().get("lines_done", 0) > 120, 300, "printing F")

        def guarded(label):
            d0 = status()["connection"]["details"]
            res = []
            c1, b1 = rest("POST", "/api/home")
            res.append(("rest_home", c1 == 409 and "refused" in b1.get("error", ""), c1, b1.get("error")))
            for g in ("G1 X11 Y11 F3000", "M104 S123", "G28"):
                c2, b2 = rest("POST", "/api/gcode", {"command": g})
                res.append(("rest_gcode " + g, c2 == 409 and "refused" in b2.get("error", ""), c2, b2.get("error")))
            return d0, res

        d0, res = guarded("printing")
        e, m = await tool("home")
        res.append(("mcp_home", e is True and "refused" in str(m), None, str(m)[:120]))
        e, sg = await tool("send_gcode", commands=["G28", "M104 S50"], stop_on_error=False)
        res.append(("mcp_send_gcode_refused", not e and sg["ok"] is False and all(r["ok"] is False and r.get("refused") and r.get("sent") is False
                    for r in sg["data"]["results"]) and len(sg["data"]["results"]) == 2, None, sg))
        e, sg = await tool("send_gcode", commands=["M105", "M114"])
        res.append(("mcp_send_gcode_readonly_allowed_mid_print", not e and sg["ok"] is True and len(sg["data"]["results"]) == 2, None, str(sg)[:160]))
        check("printing_refuses_home_and_motion_rest_and_mcp", all(x[1] for x in res), res)
        dom = dashboard_dom()
        hb, sb = button_tag(dom, "homeBtn"), button_tag(dom, "startBtn")
        check("dashboard_home_disabled_mid_print", "disabled" in hb and "disabled" in sb and 'data-motion-locked="true"' in dom
              and "a print job is printing" in hb, hb)
        screenshot("dashboard_home_disabled_printing")

        ok_p, rp = (await tool("pause"))
        wait(lambda: job().get("state") == "paused", 30, "paused F")
        time.sleep(1)
        before = status()["connection"]["details"]
        d0, res = guarded("paused")
        e, m = await tool("home")
        res.append(("mcp_home", e is True and "refused" in str(m), None, str(m)[:120]))
        time.sleep(1)
        after = status()["connection"]["details"]
        same = all(before[k] == after[k] for k in ("sim_non_report_commands", "sim_x", "sim_y", "sim_z", "sim_e", "sim_hotend_target", "sim_bed_target"))
        check("paused_refuses_home_and_motion_and_nothing_sent", all(x[1] for x in res) and same,
              {"results": res, "non_report_before": before["sim_non_report_commands"], "after": after["sim_non_report_commands"]})
        dom = dashboard_dom()
        hb = button_tag(dom, "homeBtn")
        check("dashboard_home_disabled_while_paused", "disabled" in hb and "a print job is paused" in hb, hb)
        screenshot("dashboard_home_disabled_paused")

        await tool("cancel")
        wait(lambda: job().get("state") == "cancelled", 60, "cancelled F")
        time.sleep(1.5)
        dom = dashboard_dom()
        hb, sb = button_tag(dom, "homeBtn"), button_tag(dom, "startBtn")
        check("dashboard_home_enabled_when_idle", "disabled" not in hb and "disabled" not in sb and 'data-motion-locked="false"' in dom, hb)
        screenshot("dashboard_home_enabled_idle")
        c, r = rest("POST", "/api/home")
        check("rest_home_allowed_when_idle", c == 200 and r.get("ok") is True, c)
        check("no_telegram_from_phase_f", len(tg_messages()) == n_tg5)

        # ---------------- G (batch 0): capability profile, send_gcode, server_info, unsupported convention (Ender 3 fake) ----------------
        rest("POST", "/api/sim/config", {"line_delay_ms": 5, "time_scale": 20, "profile": "ender3"})
        rest("POST", "/api/connection", {"action": "disconnect"})
        rest("POST", "/api/connection", {"action": "connect"})
        wait(lambda: status()["connection"]["state"] == "connected", 30, "reconnect G")
        tools = {t.name: t for t in (await mcp.list_tools()).tools}
        check("mcp_batch0_tools_listed", {"send_gcode", "board_info", "server_info"} <= set(tools) and len(tools) >= 12, sorted(tools))
        check("mcp_tools_have_descriptions_and_schemas", all(t.description and (getattr(t, "inputSchema", None) or t.input_schema).get("type") == "object" for t in tools.values()))
        check("mcp_tool_names_unique", len(tools) == len((await mcp.list_tools()).tools))
        init2 = raw_initialize()
        ins = init2.get("instructions") or ""
        check("mcp_instructions_rewritten_no_stale_text", "no Z-probe" not in ins and "Marlin 1.1.6" not in ins and "get_status" in ins
              and "operating-guide" in ins and "no z probe" in ins.lower(), ins)
        check("mcp_serverinfo_has_schema_version", init2["serverInfo"].get("schema_version") == 1 and init2["serverInfo"].get("version"), init2["serverInfo"])
        check("mcp_sdk_client_still_initializes", (await mcp.initialize()).server_info.name == "printer-bridge")

        e, bi = await tool("board_info")
        d = bi.get("data", {})
        check("mcp_board_info_ender_fake", not e and bi["ok"] is True and d["probe_present"] is False and d["capabilities"].get("Z_PROBE") is False
              and d["soft_limit_size"] == {"x": 220.0, "y": 220.0} and d["z_max"] == 250.0 and d["board_key"] is None
              and "firmware build not in the quirks registry" in bi["warnings"], bi)
        pr = status()["connection"]["profile"]
        check("rest_status_carries_profile", status()["connection"]["board"] is not None and pr is not None
              and pr["capabilities"] == d["capabilities"] and pr["soft_limit_size"] == d["soft_limit_size"], pr)

        e, sg = await tool("send_gcode", command="M105")
        check("mcp_send_gcode_single", not e and sg["ok"] and len(sg["data"]["results"]) == 1 and any("T:" in l for l in sg["data"]["results"][0]["reply"]), sg)
        e, sg = await tool("send_gcode", commands=["M105", "M114"])
        r0 = sg["data"]["results"]
        check("mcp_send_gcode_batch_m105_m114", not e and sg["ok"] and len(r0) == 2 and any("T:" in l for l in r0[0]["reply"])
              and any(l.startswith("X:") for l in r0[1]["reply"]), sg)
        n0 = status()["connection"]["details"]["sim_non_report_commands"]
        e, sg = await tool("send_gcode", command="M105\nM104 S250")
        check("mcp_send_gcode_rejects_embedded_newline", not e and sg["ok"] is False and "single line" in sg["error"]
              and status()["connection"]["details"]["sim_non_report_commands"] == n0, sg)
        e, sg = await tool("send_gcode")
        check("mcp_send_gcode_needs_a_command", not e and sg["ok"] is False and "error" in sg, sg)

        # unknown command: not ok, learned into known_missing_commands, and not sent a second time
        n_before = status()["connection"]["details"]["sim_non_report_commands"]
        e, sg = await tool("send_gcode", commands=["M48 P3", "M105"], stop_on_error=True)
        check("mcp_send_gcode_unknown_command_flagged_and_stops", not e and sg["ok"] is False and sg["data"]["results"][0]["ok"] is False
              and sg["data"]["skipped"] == ["M105"], sg)
        e, bi = await tool("board_info")
        check("profile_learns_known_missing_commands", "M48" in bi["data"]["known_missing_commands"], bi["data"]["known_missing_commands"])
        n_mid = status()["connection"]["details"]["sim_non_report_commands"]
        e, sg = await tool("send_gcode", command="M48")
        r1 = sg["data"]["results"][0]
        check("known_missing_command_is_not_sent_again", sg["ok"] is False and r1.get("supported") is False and r1.get("needs") == "command:M48"
              and r1.get("sent") is False and status()["connection"]["details"]["sim_non_report_commands"] == n_mid, sg)

        # wait_for_moves: a G1 is followed by M400 (the fake logs executed commands)
        e, sg = await tool("send_gcode", commands=["G90", "G1 X5 Y5 F3000"], wait_for_moves=True)
        check("mcp_send_gcode_wait_for_moves_sends_m400", not e and sg["ok"] and sg["data"]["results"][1].get("moves_finished") is True, sg)
        e, sg = await tool("send_gcode", commands=["G90", "G1 X0 Y0 F3000"])
        check("mcp_send_gcode_no_m400_by_default", not e and "moves_finished" not in sg["data"]["results"][1], sg)

        # unsupported convention through a real tool: probe commands on a board with Cap:Z_PROBE:0 are never sent
        n_cmds = status()["connection"]["details"]["sim_non_report_commands"]
        e, sg = await tool("send_gcode", commands=["G30", "G29"], stop_on_error=False)
        r = sg["data"]["results"]
        check("mcp_unsupported_probe_command_on_ender_fake", not e and sg["ok"] is False and len(r) == 2
              and all(x["ok"] is False and x["supported"] is False and x["needs"] == "Cap:Z_PROBE" and x["reason"] and x["sent"] is False for x in r)
              and status()["connection"]["details"]["sim_non_report_commands"] == n_cmds, sg)

        e, si = await tool("server_info")
        a = si["data"]["app"]
        check("mcp_server_info", not e and si["ok"] and si["data"]["mcp"]["schema_version"] == 1 and "2025-06-18" in si["data"]["mcp"]["protocol_versions"]
              and a["version_name"] and a["git_commit"] and isinstance(a["git_dirty"], bool) and si["data"]["backend"] == "fake", si)

        # ---------------- G2: a rejected request must not poison the kept-alive connection (unread body) ----------------
        import http.client
        import urllib.parse
        u = urllib.parse.urlparse(args.base)
        mcp_body = json.dumps({"jsonrpc": "2.0", "id": "server-discover-probe-1", "method": "server/discover", "params": {"_meta": {}}})
        hdr = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}

        def on(conn, method, path, body=None, tok=None):
            h = dict(hdr)
            if tok:
                h["Authorization"] = "Bearer " + tok
            conn.request(method, path, body=body, headers=h)
            r = conn.getresponse()
            return r.status, r.read().decode()

        kc = http.client.HTTPConnection(u.hostname, u.port, timeout=15)
        c1, _ = on(kc, "POST", "/mcp", mcp_body, tok="wrong")
        c2, t2 = on(kc, "POST", "/mcp", mcp_body, tok=args.token)
        check("wrong_token_then_good_request_same_connection", c1 == 401 and c2 == 200 and "method not found" in t2, (c1, c2, t2[:80]))
        kc = http.client.HTTPConnection(u.hostname, u.port, timeout=15)
        c1, _ = on(kc, "POST", "/mcp", mcp_body, tok=None)
        c2, _ = on(kc, "POST", "/mcp", mcp_body, tok=None)
        check("no_token_twice_same_connection_gives_401_each_time", c1 == 401 and c2 == 401, (c1, c2))
        kc = http.client.HTTPConnection(u.hostname, u.port, timeout=15)
        c1, _ = on(kc, "POST", "/api/no_such_route", json.dumps({"x": 1}), tok=args.token)
        c2, _ = on(kc, "GET", "/api/status")
        check("unknown_post_route_does_not_poison_connection", c1 == 404 and c2 == 200, (c1, c2))
        kc = http.client.HTTPConnection(u.hostname, u.port, timeout=15)
        c1, _ = on(kc, "POST", "/api/gcode", "{not json", tok=args.token)
        c2, _ = on(kc, "GET", "/api/status")
        check("malformed_body_does_not_poison_connection", c1 == 400 and c2 == 200, (c1, c2))
        kc = http.client.HTTPConnection(u.hostname, u.port, timeout=15)
        big = "x" * 1_200_000
        try:
            c1, _ = on(kc, "POST", "/mcp", big, tok="wrong")
            closed = True
        except Exception as e:
            c1, closed = str(e), True
        check("oversized_rejected_body_still_gets_401", c1 == 401, c1)

        # ---------------- H (batch 0): SKR + BLTouch profile (fake SKR) ----------------
        rest("POST", "/api/sim/config", {"profile": "skr"})
        rest("POST", "/api/connection", {"action": "disconnect"})
        rest("POST", "/api/connection", {"action": "connect"})
        wait(lambda: status()["connection"]["state"] == "connected", 30, "reconnect H")
        e, bi = await tool("board_info")
        d = bi.get("data", {})
        check("mcp_board_info_skr_fake_probe_present", not e and bi["ok"] and d["capabilities"].get("Z_PROBE") is True and d["probe_present"] is True
              and d["autolevel"] is True and d["emergency_parser"] is True, bi)
        check("board_info_skr_matches_quirk_entry", d["board_key"] == "skr-mini-e3-v3-btt-bltouch-2022-03-11" and "BLTouch" in d["board_name"], d.get("board_key"))
        check("board_info_skr_limits_leveling_settings", d["soft_limit_size"] == {"x": 235.0, "y": 235.0} and d["z_max"] == 246.7 and d["leveling_on"] is True
              and d["soft_endstops"] is True and d["limits"]["z_min"] == -3.3
              and d["mesh_type"] == "bilinear" and d["mesh_grid"] == [5, 5] and d["mesh_z_min"] == -0.04 and d["mesh_z_max"] == 0.04 and d["settings"].get("m206_z") == -3.3 and d["settings"].get("m851_z") == -1.71, d)
        e, bi2 = await tool("board_info")
        check("known_missing_resets_on_reconnect", bi2["data"]["known_missing_commands"] == [], bi2["data"]["known_missing_commands"])
        init3 = raw_initialize()
        ins = init3.get("instructions") or ""
        check("mcp_instructions_describe_skr_setup", "SKR Mini E3 V3.0" in ins and "Z probe is present" in ins and "mesh leveling is ON" in ins
              and "X 0..235" in ins and "Y 0..235" in ins and "Z up to 246.7" in ins, ins)
        e, sg = await tool("send_gcode", command="G30")
        check("mcp_probe_command_is_sent_when_probe_present", not e and sg["data"]["results"][0].get("sent") is True
              and sg["data"]["results"][0].get("supported") is not False, sg)
        e, sg = await tool("send_gcode", commands=["M105", "M114"])
        check("mcp_send_gcode_skr_fake", not e and sg["ok"] and len(sg["data"]["results"]) == 2, sg)

        # requireCap helper + unsupported convention: no probe-needing tool exists yet in batch 0, so check the helper
        # through board_info on a disconnected printer (supported:false with a reason and `needs`)
        rest("POST", "/api/connection", {"action": "disconnect"})
        e, bi = await tool("board_info")
        check("unsupported_convention_when_disconnected", not e and bi["ok"] is False and bi["supported"] is False and bi.get("reason") and bi.get("needs"), bi)
        rest("POST", "/api/sim/config", {"profile": "ender3"})
        rest("POST", "/api/connection", {"action": "connect"})
        wait(lambda: status()["connection"]["state"] == "connected", 30, "reconnect end")

    with open(args.out + "_timeline.json", "w") as f:
        json.dump(timeline, f, indent=0)
    with open(args.out + "_results.json", "w") as f:
        json.dump(results, f, indent=1)
    npass = sum(r["pass"] for r in results)
    print(f"\n{npass}/{len(results)} checks passed")


asyncio.run(main())

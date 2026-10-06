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

    with open(args.out + "_timeline.json", "w") as f:
        json.dump(timeline, f, indent=0)
    with open(args.out + "_results.json", "w") as f:
        json.dump(results, f, indent=1)
    npass = sum(r["pass"] for r in results)
    print(f"\n{npass}/{len(results)} checks passed")


asyncio.run(main())

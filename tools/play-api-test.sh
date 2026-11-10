#!/usr/bin/env bash
# Runs tools/play-api.sh against a fake Play API and checks what it sends. Born from
# #25: upload never sent releaseNotes, so Play showed an empty "What's new" for 0.2.3.
# Exit 0: all checks passed. Exit 1: a check failed. Exit 2: cannot run.
set -uo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
for t in python3 curl openssl; do command -v "$t" >/dev/null || { echo "cannot run: $t missing"; exit 2; }; done
W=$(mktemp -d); srv=
cleanup() { [ -n "$srv" ] && kill "$srv"; rm -rf "$W"; }
trap cleanup EXIT

cat > "$W/fake.py" <<'PY'
import json, sys, http.server
log = sys.argv[2]
class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def reply(self, obj):
        b = json.dumps(obj).encode(); self.send_response(200)
        self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(b)))
        self.end_headers(); self.wfile.write(b)
    def body(self):
        n = int(self.headers.get('Content-Length') or 0); return self.rfile.read(n)
    def record(self, data):
        with open(log, 'a') as f: f.write(json.dumps({'m': self.command, 'p': self.path, 'b': data}) + '\n')
    def do_POST(self):
        b = self.body(); self.record(None)
        if self.path.endswith('/edits'): self.reply({'id': 'e1'})
        elif '/bundles' in self.path: self.reply({'versionCode': 5})
        else: self.reply({})
    def do_PUT(self):
        b = self.body(); self.record(b.decode('utf-8')); self.reply({})
    def do_GET(self):
        self.record(None)
        self.reply({'track': 'internal', 'releases': [{'versionCodes': ['5'], 'status': 'completed'}]})
    def do_DELETE(self):
        self.record(None); self.reply({})
http.server.HTTPServer(('127.0.0.1', int(sys.argv[1])), H).serve_forever()
PY
port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
python3 "$W/fake.py" "$port" "$W/log" & srv=$!
for _ in $(seq 50); do curl -s -o /dev/null "http://127.0.0.1:$port/" && break; sleep 0.1; done
kill -0 "$srv" || { echo "cannot run: fake API did not start"; exit 2; }

M="$W/meta"; mkdir -p "$M/en-US/changelogs" "$M/fa/changelogs"
printf 'Clearer messages\n' > "$M/en-US/changelogs/5.txt"
printf 'پیام روشن‌تر\n' > "$M/fa/changelogs/5.txt"
printf 'fake bundle' > "$W/app.aab"
export PLAY_API="http://127.0.0.1:$port" PLAY_TOKEN=test
pass=0; failed=0
ok() { echo "ok: $1"; pass=$((pass+1)); }
bad() { echo "FAIL: $1"; failed=$((failed+1)); }
# The last track PUT's releases[0].releaseNotes as "lang=text;…", or "none"
notes() { python3 -c '
import json,sys
puts=[json.loads(l) for l in open(sys.argv[1]) if json.loads(l)["m"]=="PUT"]
if not puts: print("no PUT"); sys.exit()
r=json.loads(puts[-1]["b"])["releases"][0]
print(";".join("%s=%s|%s" % (n["language"], n["text"], r["status"]) for n in r.get("releaseNotes",[])) or "none")' "$W/log"; }

: > "$W/log"
bash "$here/tools/play-api.sh" upload "$W/app.aab" internal 5 completed "$M" > "$W/out" 2>&1 \
  && ok "upload exits 0" || { bad "upload exits non-zero"; cat "$W/out"; }
got=$(notes)
[ "$got" = "en-US=Clearer messages|completed;fa=پیام روشن‌تر|completed" ] && ok "upload sends both changelogs" || bad "upload notes: $got"

: > "$W/log"
bash "$here/tools/play-api.sh" notes internal 5 "$M" > "$W/out" 2>&1 \
  && ok "notes exits 0" || { bad "notes exits non-zero"; cat "$W/out"; }
got=$(notes)
[ "$got" = "en-US=Clearer messages|completed;fa=پیام روشن‌تر|completed" ] && ok "notes keeps status and sends both" || bad "notes notes: $got"
grep -q '/bundles' "$W/log" && bad "notes uploaded a bundle" || ok "notes uploads no bundle"

rm "$M/en-US/changelogs/5.txt"; : > "$W/log"
bash "$here/tools/play-api.sh" upload "$W/app.aab" internal 5 completed "$M" > "$W/out" 2>&1
rc=$?
[ "$rc" -ne 0 ] && ok "missing en-US refused (exit $rc)" || bad "missing en-US accepted"
grep -q '/bundles' "$W/log" && bad "bundle uploaded before the notes were checked" || ok "nothing uploaded without en-US"

echo "play-api-test: $pass passed, $failed failed"
[ "$pass" -eq 7 ] || { [ "$failed" -eq 0 ] && echo "only $pass of 7 checks ran"; exit 1; }
[ "$failed" -eq 0 ]

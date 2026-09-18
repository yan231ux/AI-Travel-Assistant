"""P0 回归探针：停用账号的旧 token 必须立刻失效（停用即失效）。

覆盖：停用前可写 → 停用后旧 token 全部 401 → 重新登录被拒 → 恢复后旧 token 可写。
用法：后端已在 8080 运行，直接 python probe_suspend_token.py
"""
import json
import random
import string
import subprocess
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
PASS = 0
FAIL = 0


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {name} {detail}")
    else:
        FAIL += 1
        print(f"  [FAIL] {name} {detail}")


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except Exception:
            return e.code, {}


def token_of(resp):
    d = resp.get("data") or resp
    return d.get("token") or d.get("access_token") or d.get("accessToken")


def db(sql):
    out = subprocess.run(
        ["docker", "exec", "trip-planner-mysql", "mysql", "-uroot", "-proot",
         "--default-character-set=utf8mb4", "trip_planner", "-N", "-e", sql],
        capture_output=True, text=True)
    return out.stdout.strip()


print("=== P0 回归探针：停用账号旧 token 即时失效 ===")
st, resp = call("POST", "/auth/admin-login", {"username": "admin", "password": "admin123"})
if st != 200:
    st, resp = call("POST", "/auth/login", {"username": "admin", "password": "admin123"})
admin_tok = token_of(resp)
check("管理员登录", st == 200 and bool(admin_tok), f"status={st}")

uname = "suspend_probe_" + "".join(random.choices(string.ascii_lowercase, k=6))
st, resp = call("POST", "/auth/register",
                {"username": uname, "password": "Test123456", "nickname": "停用探针"})
victim_tok = token_of(resp)
vid = db(f"SELECT id FROM users WHERE username='{uname}'")
check("注册被测账号", st == 200 and bool(victim_tok) and bool(vid), f"id={vid}")

# 停用前：应当可写
st, r = call("POST", "/users/1/follow", {}, token=victim_tok)
check("停用前 旧token 可写", st == 200, f"status={st}")

# 管理员停用
st, r = call("POST", f"/admin/users/{vid}/govern",
             {"action": "SUSPEND", "reason": "P0 回归探针"}, token=admin_tok)
check("管理员执行停用", st == 200, f"status={st} {r.get('message','')}")

# 停用后：旧 token 必须全部 401
st, r = call("POST", "/users/2/follow", {}, token=victim_tok)
check("停用后 旧token 关注被拒(401)", st == 401, f"status={st}")
st, r = call("POST", "/user/behavior",
             {"itemType": "SPOT", "actionType": "SAVE", "itemName": "P0探针点"}, token=victim_tok)
check("停用后 旧token 行为上报被拒(401)", st == 401, f"status={st}")
st, r = call("PUT", "/user/profile/questionnaire",
             {"travelStyles": ["自然风景"], "pace": "休闲"}, token=victim_tok)
check("停用后 旧token 改画像被拒(401)", st == 401, f"status={st}")
st, r = call("GET", "/auth/me", token=victim_tok)
check("停用后 旧token 取登录态被拒(401)", st == 401, f"status={st}")

# 重新登录仍被拒（原有行为不能退化）
st, r = call("POST", "/auth/login", {"username": uname, "password": "Test123456"})
check("停用后 重新登录被拒(400)", st == 400, f"status={st} {r.get('message','')}")

# 恢复后：旧 token 应恢复可用
st, r = call("POST", f"/admin/users/{vid}/govern",
             {"action": "RESTORE", "reason": "探针清理"}, token=admin_tok)
check("管理员恢复账号", st == 200, f"status={st}")
st, r = call("POST", "/user/behavior",
             {"itemType": "SPOT", "actionType": "SAVE", "itemName": "恢复后写入"}, token=victim_tok)
check("恢复后 旧token 可写", st == 200, f"status={st}")

# 清理
db(f"DELETE FROM user_follow WHERE user_id='{vid}';"
   f"DELETE FROM user_behavior WHERE user_id='{vid}';"
   f"DELETE FROM user_preference WHERE user_id='{vid}';"
   f"DELETE FROM user_profile WHERE user_id='{vid}';"
   f"DELETE FROM users WHERE id={vid};")
print(f"\n=== 结果：{PASS} 通过 / {FAIL} 失败 ===")
raise SystemExit(1 if FAIL else 0)

# -*- coding: utf-8 -*-
"""结果页持久化探针的种子/清理助手。

为什么需要它：端到端复验"生成完的行程不能丢"这件事，若走真实生成会消耗模型额度、耗时数分钟且受额度波动影响。
改为用**一份真实已保存行程**做种子（从 trip_record 导出），由探针注册一次性账号把它存为自己的行程，
再经「历史列表 → 查看详情」走真实读取/写入口。

用法：
  python frontend/probe/seed_trip.py            # 导出种子 → 仓库根目录 _seed_trip.txt
  python frontend/probe/seed_trip.py --clean    # 清掉探针账号(rdprobe_%)及其全部数据
  python frontend/probe/seed_trip.py --list     # 看当前还剩哪些探针账号

⚠️ 只删 rdprobe_% 前缀的账号与 probe-trip-% 的行，不碰任何真实/演示账号。
"""
import io
import json
import os
import subprocess
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
SEED = os.path.join(REPO, "_seed_trip.txt")

MYSQL = ["docker", "exec", "trip-planner-mysql", "mysql", "-uroot", "-proot",
         "--default-character-set=utf8mb4", "trip_planner", "-N", "-B", "-e"]

# 库里带 user_id 的表（information_schema 查得，清理时逐表清）
USER_TABLES = [
    "ab_assignment", "agent_plan_archive", "candidate_evidence", "post_comment",
    "post_feed_log", "post_interaction", "recommendation_log", "spot_feed_log",
    "travel_event", "travel_post", "trip_record", "user_behavior", "user_follow",
    "user_preference", "user_profile", "user_spot_favorite",
]


def q(sql: str) -> str:
    p = subprocess.run(MYSQL + [sql], capture_output=True)
    out = p.stdout.decode("utf-8", "replace").strip()
    err = p.stderr.decode("utf-8", "replace").strip()
    if p.returncode != 0:
        raise RuntimeError("SQL 失败: %s\n%s" % (sql, err))
    return out


def export_seed() -> None:
    """挑一份天数最多的真实行程当种子（避免 1 天的残缺样本）。"""
    tid = q("SELECT trip_id FROM trip_record ORDER BY id DESC LIMIT 1;").splitlines()
    # 优先找天数 >=3 的：按 itinerary_json 长度近似挑（大 = 天多/内容全）
    cand = q("SELECT trip_id FROM trip_record ORDER BY CHAR_LENGTH(itinerary_json) DESC LIMIT 1;")
    trip_id = (cand or (tid[-1] if tid else "")).strip()
    if not trip_id:
        raise SystemExit("库里没有 trip_record，无法导出种子")
    raw = q("SELECT itinerary_json FROM trip_record WHERE trip_id='%s';" % trip_id)
    it = json.loads(raw)
    io.open(SEED, "w", encoding="utf-8", newline="\n").write(
        json.dumps({"itinerary": it, "trace": []}, ensure_ascii=False))
    print("种子已导出 -> %s" % SEED)
    print("  trip_id=%s destination=%s days=%d chars=%d"
          % (trip_id, it.get("destination"), len(it.get("days") or []), len(raw)))


def probe_ids() -> list:
    out = q("SELECT id FROM users WHERE username LIKE 'rdprobe%';")
    return [x.strip() for x in out.splitlines() if x.strip()]


def clean() -> None:
    ids = probe_ids()
    trips = q("SELECT COUNT(*) FROM trip_record WHERE trip_id LIKE 'probe-trip-%';").strip()
    if not ids and trips in ("", "0"):
        print("没有需要清理的探针账号 / 行程。")
        return
    if ids:
        # ⚠️ user_id 在不同表里类型不一致（trip_record.user_id 是 varchar），
        # 统一按字符串带引号比较，MySQL 对数值列会自动转换。
        idlist = ",".join("'%s'" % i for i in ids)
        for t in USER_TABLES:
            try:
                q("DELETE FROM %s WHERE user_id IN (%s);" % (t, idlist))
            except RuntimeError as e:
                print("  (跳过 %s: %s)" % (t, str(e).splitlines()[0]))
        q("DELETE FROM users WHERE id IN (%s);" % idlist)
    q("DELETE FROM trip_record WHERE trip_id LIKE 'probe-trip-%';")
    print("已清理账号 %s 个（ids=%s）与 probe-trip-%% 行程 %s 行。" % (len(ids), ",".join(ids) or "-", trips))
    print("剩余探针账号: %s" % (q("SELECT COUNT(*) FROM users WHERE username LIKE 'rdprobe%';").strip()))


def main() -> None:
    args = sys.argv[1:]
    if "--clean" in args:
        clean()
    elif "--list" in args:
        print("探针账号:", q("SELECT id, username FROM users WHERE username LIKE 'rdprobe%';") or "(无)")
    else:
        export_seed()


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""一次性数据修复：归档"绑定版本已被处置、却仍无决策地挂在 REVIEW"的审核任务。

背景（TROUBLESHOOTING §38 缺陷②）：旧代码在内容侧通过/驳回修改版本时，不会终结绑定其上的
审核任务，于是任务永远停在 status=REVIEW 且 decision 为空 —— 既不属于流水线（不会自收尾），
也过不了"未决策"过滤，只能永久赖在人工复核队列里，导致「AI 筛选队列」与「内容审核页」对不上。

新代码（PostService.closeModerationTaskOfRevision）已堵住新增；本脚本清理历史残留。
幂等：只动 status=REVIEW + decision IS NULL + 绑定版本已 APPROVED/REJECTED 的行，并写审计留痕。

用法：python repair_stale_moderation_tasks.py   （需本地 docker 容器 trip-planner-mysql 在跑）
"""
import subprocess

CONTAINER = "trip-planner-mysql"
OUT = r"C:\Users\曾\Desktop\agent\AI-Travel-Assistant\backend\_repair_out.txt"

Q_SELECT = """
SELECT t.id, t.target_type, t.target_id, t.revision_id, r.status
FROM content_moderation_task t
JOIN travel_post_revision r ON r.id = t.revision_id
WHERE t.status = 'REVIEW' AND t.decision IS NULL
  AND r.status IN ('APPROVED','REJECTED')
ORDER BY t.id
"""


def sql(q):
    r = subprocess.run(
        ["docker", "exec", CONTAINER, "mysql", "-uroot", "-proot",
         "--default-character-set=utf8mb4", "-N", "-B", "-e", q, "trip_planner"],
        capture_output=True)
    return r.stdout.decode("utf-8", "replace"), r.stderr.decode("utf-8", "replace")


def esc(s):
    return s.replace("'", "''")


def main():
    lines = []
    out, _ = sql(Q_SELECT)
    rows = [ln.split("\t") for ln in out.strip().splitlines() if ln.strip()]
    lines.append("待归档任务：%d 条" % len(rows))
    for task_id, target_type, target_id, rev_id, rev_status in rows:
        decision = "APPROVE" if rev_status == "APPROVED" else "REJECT"
        reason = "随修改版本一并归档（历史残留一次性修复）"
        det = "target=%s:%s,revision=%s,decision=%s,note=one-time-repair" % (
            target_type, target_id, rev_id, decision)
        upd = (
            "UPDATE content_moderation_task SET decision='%s', decision_by='system:revision',"
            " decision_reason='%s', decided_at=NOW() WHERE id=%s;"
            % (decision, esc(reason), task_id))
        aud = (
            "INSERT INTO audit_log (actor_id, category, action, target_type, target_id, detail)"
            " VALUES ('system:revision','CONTENT','moderation_closed_by_revision','moderation','%s','%s');"
            % (task_id, esc(det)))
        _, e1 = sql(upd)
        _, e2 = sql(aud)
        lines.append("task#%s → %s（revision %s %s） update_err=%r audit_err=%r"
                     % (task_id, decision, rev_id, rev_status,
                        e1.strip()[:200], e2.strip()[:200]))

    out2, _ = sql(Q_SELECT)
    rest = [ln for ln in out2.strip().splitlines() if ln.strip()]
    lines.append("修复后剩余：%d 条" % len(rest))
    with open(OUT, "w", encoding="utf-8") as fp:
        fp.write("\n".join(lines) + "\n")
    print("written", OUT)


if __name__ == "__main__":
    main()

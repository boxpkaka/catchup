from __future__ import annotations

import argparse
import fcntl
import logging
import os
import sys
from datetime import datetime, timezone

from .collector import collect, due
from .config import load_config, load_env
from .sources import REGISTRY, build_sources
from .store import Store


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="catchup", description="Catchup：替你盯住信息源，只告诉你重要的变化")
    parser.add_argument("-c", "--config", default=os.environ.get("CATCHUP_CONFIG", "config.json"))
    parser.add_argument("-v", "--verbose", action="store_true")
    sub = parser.add_subparsers(dest="command", required=True)

    p_collect = sub.add_parser("collect", help="采集到了采样间隔的来源，写入数据库")
    p_collect.add_argument("--only", nargs="+", metavar="SOURCE", help="只运行指定的来源实例，不看采样间隔")
    p_collect.add_argument("--no-update", action="store_true", help="只采集，不做事件、简报和推送")
    p_update = sub.add_parser("update", help="不采集，只做事件归并、简报和推送")
    p_update.add_argument("--no-push", action="store_true", help="不推送到手机")
    p_devices = sub.add_parser("devices", help="查看或批准 App 设备")
    p_devices.add_argument("action", nargs="?", choices=["list", "approve", "revoke"], default="list")
    p_devices.add_argument("id", nargs="?", type=int)
    p_serve = sub.add_parser("serve", help="启动只读预览页")
    p_serve.add_argument("--host")
    p_serve.add_argument("--port", type=int)
    sub.add_parser("sources", help="列出已配置的来源和可用的来源类型")

    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    config = load_config(args.config)
    load_env(config.root / ".env")
    sources = build_sources(config.sources)

    if args.command == "collect":
        store = Store(config.db_path)
        # cron 每 10 分钟一轮，上一轮（比如 Reddit 限流、写简报）没跑完时这一轮直接跳过，避免重复处理和重复推送
        lock = open(config.db_path.parent / "collect.lock", "w")
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            logging.getLogger(__name__).warning("previous collect still running, skipped")
            return 0
        if args.only:
            unknown = set(args.only) - set(sources)
            if unknown:
                parser.error(f"unknown source(s): {', '.join(sorted(unknown))}")
            sources = {k: v for k, v in sources.items() if k in args.only}
        else:
            sources = due(sources, store, datetime.now(timezone.utc))
        ok = collect(sources, store)
        if not args.no_update:
            from .pipeline.update import update
            update(config, store)
        return 0 if ok else 1

    if args.command == "update":
        from .pipeline.update import update
        update(config, Store(config.db_path), deliver=not args.no_push)
        return 0

    if args.command == "devices":
        store = Store(config.db_path)
        if args.action != "list":
            if args.id is None:
                parser.error("approve/revoke 需要设备 id")
            if not store.set_device_approved(args.id, args.action == "approve"):
                parser.error(f"没有设备 {args.id}")
        for d in store.devices():
            print(f"{d['id']:>3}  {'已批准' if d['approved'] else '待确认'}  {d['name']}  创建 {d['created']}  最近 {d['last_seen']}")
        return 0

    if args.command == "serve":
        from .web.server import serve
        serve(config, args.host or config.web.get("host", "127.0.0.1"), args.port or config.web.get("port", 2591))
        return 0

    if args.command == "sources":
        for name, src in sources.items():
            print(f"{name:<16} type={src.type:<16} tier={src.tier:<9} every={src.interval:<4} kind={src.kind}")
        print(f"\navailable types: {', '.join(sorted(REGISTRY))}")
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())

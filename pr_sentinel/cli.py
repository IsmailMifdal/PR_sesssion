"""Point d'entrée : ``pr-sentinel migrate | worker | serve``."""

from __future__ import annotations

import argparse
import logging
import threading

from pr_sentinel.config import get_settings


def _migrate() -> None:
    from pr_sentinel.db import migrate

    applied = migrate(get_settings().database_url)
    print(f"migrations appliquées : {', '.join(applied) or 'aucune'}")


def _worker() -> None:
    from pr_sentinel.db import create_pool
    from pr_sentinel.github import GitHubApp
    from pr_sentinel.scanners import default_scanners
    from pr_sentinel.worker import Worker, install_signal_handlers

    settings = get_settings()
    pool = create_pool(settings.database_url, max_size=2)
    github = GitHubApp(settings.github_app_id, settings.private_key_pem(), api_url=settings.github_api_url)
    stop = threading.Event()
    install_signal_handlers(stop)
    try:
        Worker(pool, github, default_scanners(settings), settings).run_forever(stop)
    finally:
        pool.close()


def _serve(host: str, port: int) -> None:
    import uvicorn

    from pr_sentinel.app import create_app

    uvicorn.run(create_app(), host=host, port=port, proxy_headers=True)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="pr-sentinel")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("migrate", help="applique les migrations SQL")
    sub.add_parser("worker", help="lance le worker de scan")
    serve = sub.add_parser("serve", help="lance l'API webhook")
    serve.add_argument("--host", default="0.0.0.0")  # noqa: S104 - écoute dans le conteneur
    serve.add_argument("--port", type=int, default=8000)
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    if args.command == "migrate":
        _migrate()
    elif args.command == "worker":
        _worker()
    else:
        _serve(args.host, args.port)


if __name__ == "__main__":
    main()

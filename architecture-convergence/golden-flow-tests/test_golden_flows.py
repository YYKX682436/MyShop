"""Local-only executable oracle for the Phase 1 convergence contract.

These tests exercise a durable SQLite fake for both sides. They do not invoke
the current MyShop workers or the production Agent; the report records that
boundary explicitly so a green run is not mistaken for staging acceptance.
"""
from __future__ import annotations

import hashlib
import json
import sqlite3
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator


def canonical(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def digest(value: Any) -> str:
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


CONTRACT_FIXTURE_PATH = (
    Path(__file__).resolve().parents[2]
    / "apps/api/src/test/resources/contracts/ensure_listing_v2.json"
)
SNAPSHOT = json.loads(CONTRACT_FIXTURE_PATH.read_text(encoding="utf-8"))["desired_snapshot"]


class IdempotencyConflict(Exception):
    pass


class FakeAgent:
    """SQLite-backed Agent oracle: durable ledger plus fake provider state."""

    def __init__(self, path: Path):
        self.path = path
        with self.db() as db:
            db.executescript(
                """
                CREATE TABLE IF NOT EXISTS listing_ops(
                    request_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL,
                    account_id TEXT NOT NULL, listing_key TEXT NOT NULL,
                    revision INTEGER NOT NULL, desired_state TEXT NOT NULL,
                    snapshot_json TEXT, state TEXT NOT NULL,
                    external_item_id TEXT, error_code TEXT, internal_action TEXT
                );
                CREATE TABLE IF NOT EXISTS provider_items(
                    external_item_id TEXT PRIMARY KEY, account_id TEXT NOT NULL,
                    listing_key TEXT NOT NULL, state TEXT NOT NULL,
                    snapshot_json TEXT NOT NULL, last_request_id TEXT NOT NULL,
                    created_request_id TEXT NOT NULL
                );
                CREATE UNIQUE INDEX IF NOT EXISTS one_current_item
                    ON provider_items(account_id, listing_key) WHERE state = 'CURRENT';
                CREATE TABLE IF NOT EXISTS item_skus(
                    account_id TEXT NOT NULL, external_item_id TEXT NOT NULL,
                    external_sku_id TEXT NOT NULL, sku_key TEXT NOT NULL,
                    spec_values_json TEXT NOT NULL,
                    PRIMARY KEY(account_id, external_item_id, external_sku_id)
                );
                CREATE TABLE IF NOT EXISTS provider_effects(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT NOT NULL,
                    action TEXT NOT NULL, target_id TEXT NOT NULL,
                    UNIQUE(request_id, action)
                );
                CREATE TABLE IF NOT EXISTS account_state(
                    account_id TEXT PRIMARY KEY, auth_state TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS managed_items(
                    account_id TEXT NOT NULL, external_item_id TEXT NOT NULL,
                    managed_by TEXT NOT NULL,
                    PRIMARY KEY(account_id, external_item_id)
                );
                CREATE TABLE IF NOT EXISTS listing_bindings(
                    account_id TEXT NOT NULL, listing_key TEXT NOT NULL,
                    external_item_id TEXT NOT NULL, state TEXT NOT NULL,
                    PRIMARY KEY(account_id, external_item_id)
                );
                CREATE UNIQUE INDEX IF NOT EXISTS one_current_listing_binding
                    ON listing_bindings(account_id, listing_key) WHERE state = 'CURRENT';
                CREATE TABLE IF NOT EXISTS adoption_ops(
                    request_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL,
                    account_id TEXT NOT NULL, listing_key TEXT NOT NULL,
                    external_item_id TEXT NOT NULL, state TEXT NOT NULL,
                    error_code TEXT
                );
                CREATE TABLE IF NOT EXISTS agent_orders(
                    account_id TEXT NOT NULL, external_order_id TEXT NOT NULL,
                    external_item_id TEXT NOT NULL, status TEXT NOT NULL,
                    quantity INTEGER NOT NULL, amount TEXT NOT NULL, currency TEXT NOT NULL,
                    external_sku_id TEXT, spec_values_json TEXT NOT NULL,
                    placed_at TEXT, updated_at TEXT NOT NULL,
                    PRIMARY KEY(account_id, external_order_id)
                );
                CREATE TABLE IF NOT EXISTS delivery_ops(
                    request_id TEXT PRIMARY KEY, operation_type TEXT NOT NULL,
                    fingerprint TEXT NOT NULL, account_id TEXT NOT NULL,
                    external_order_id TEXT NOT NULL, state TEXT NOT NULL,
                    result_json TEXT
                );
                """
            )

    @contextmanager
    def db(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.path)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def set_auth(self, account_id: str, state: str) -> None:
        with self.db() as db:
            db.execute(
                "INSERT INTO account_state VALUES (?, ?) ON CONFLICT(account_id) DO UPDATE SET auth_state=excluded.auth_state",
                (account_id, state),
            )

    def adopt_existing_item(self, command: dict[str, Any], *, lose_response: bool = False) -> dict[str, Any]:
        fingerprint = digest({key: value for key, value in command.items() if key != "request_id"})
        with self.db() as db:
            prior = db.execute("SELECT * FROM adoption_ops WHERE request_id=?", (command["request_id"],)).fetchone()
            if prior:
                if prior["fingerprint"] != fingerprint:
                    raise IdempotencyConflict(command["request_id"])
                row = prior
            else:
                managed = db.execute(
                    "SELECT managed_by FROM managed_items WHERE account_id=? AND external_item_id=?",
                    (command["account_id"], command["external_item_id"]),
                ).fetchone()
                error_code = None
                if managed is None or managed["managed_by"] != "MYSHOP":
                    error_code = "ITEM_NOT_MANAGED_BY_MYSHOP"
                existing = db.execute(
                    "SELECT * FROM listing_bindings WHERE account_id=? AND external_item_id=?",
                    (command["account_id"], command["external_item_id"]),
                ).fetchone()
                current = db.execute(
                    "SELECT * FROM listing_bindings WHERE account_id=? AND listing_key=? AND state='CURRENT'",
                    (command["account_id"], command["listing_key"]),
                ).fetchone()
                if error_code is None and existing is not None and (
                    existing["listing_key"] != command["listing_key"] or existing["state"] != "CURRENT"
                ):
                    error_code = "LISTING_BINDING_CONFLICT"
                if error_code is None and current is not None and (
                    current["external_item_id"] != command["external_item_id"]
                ):
                    error_code = "LISTING_BINDING_CONFLICT"
                state = "FAILED" if error_code else "SUCCEEDED"
                if error_code is None and existing is None:
                    db.execute(
                        "INSERT INTO listing_bindings VALUES (?, ?, ?, 'CURRENT')",
                        (command["account_id"], command["listing_key"], command["external_item_id"]),
                    )
                    db.execute(
                        "UPDATE provider_items SET listing_key=? "
                        "WHERE account_id=? AND external_item_id=? AND state='CURRENT'",
                        (command["listing_key"], command["account_id"], command["external_item_id"]),
                    )
                db.execute(
                    "INSERT INTO adoption_ops VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (command["request_id"], fingerprint, command["account_id"], command["listing_key"],
                     command["external_item_id"], state, error_code),
                )
                row = db.execute("SELECT * FROM adoption_ops WHERE request_id=?", (command["request_id"],)).fetchone()

            response = {
                "request_id": row["request_id"], "listing_key": row["listing_key"],
                "state": row["state"], "external_item_id": row["external_item_id"],
                "error": {"code": row["error_code"]} if row["error_code"] else None,
            }
            # The durable adoption is already terminal; only its HTTP response was lost.
            if lose_response and row["state"] == "SUCCEEDED":
                response["state"] = "APPLYING"
            return response

    def ensure_listing(self, command: dict[str, Any], *, lose_response: bool = False) -> dict[str, Any]:
        fingerprint = digest({key: value for key, value in command.items() if key != "request_id"})
        snapshot = command["desired_snapshot"]
        snapshot_json = canonical(snapshot) if snapshot is not None else None
        with self.db() as db:
            prior = db.execute("SELECT * FROM listing_ops WHERE request_id=?", (command["request_id"],)).fetchone()
            if prior:
                if prior["fingerprint"] != fingerprint:
                    raise IdempotencyConflict(command["request_id"])
                if prior["state"] == "APPLYING":
                    self._reconcile_listing(db, prior)
                    prior = db.execute("SELECT * FROM listing_ops WHERE request_id=?", (command["request_id"],)).fetchone()
                return self._observation(prior)

            latest = db.execute("SELECT revision, fingerprint FROM listing_ops WHERE account_id=? AND listing_key=? ORDER BY revision DESC LIMIT 1",
                                (command["account_id"], command["listing_key"])).fetchone()
            if latest and command["revision"] == latest["revision"] and fingerprint != latest["fingerprint"]:
                raise IdempotencyConflict("revision fingerprint changed")
            stale = latest is not None and command["revision"] < latest["revision"]
            db.execute(
                "INSERT INTO listing_ops VALUES (?, ?, ?, ?, ?, ?, ?, 'APPLYING', NULL, NULL, NULL)",
                (command["request_id"], fingerprint, command["account_id"], command["listing_key"],
                 command["revision"], command["desired_state"], snapshot_json),
            )
            if stale:
                db.execute("UPDATE listing_ops SET state='FAILED', error_code='STALE_REVISION' WHERE request_id=?",
                           (command["request_id"],))
                return self._observation(db.execute("SELECT * FROM listing_ops WHERE request_id=?",
                                                    (command["request_id"],)).fetchone())
            auth = db.execute("SELECT auth_state FROM account_state WHERE account_id=?", (command["account_id"],)).fetchone()
            if auth and auth["auth_state"] != "VALID":
                db.execute("UPDATE listing_ops SET state='FAILED', error_code='AUTH_REQUIRED' WHERE request_id=?",
                           (command["request_id"],))
                return self._observation(db.execute("SELECT * FROM listing_ops WHERE request_id=?",
                                                    (command["request_id"],)).fetchone())

            current = db.execute(
                "SELECT * FROM provider_items WHERE account_id=? AND listing_key=? AND state='CURRENT'",
                (command["account_id"], command["listing_key"]),
            ).fetchone()
            if command["desired_state"] == "OFFLINE":
                if current:
                    db.execute("INSERT OR IGNORE INTO provider_effects(request_id, action, target_id) VALUES (?, 'OFFLINE', ?)",
                               (command["request_id"], current["external_item_id"]))
                    db.execute("UPDATE provider_items SET state='OFFLINE', last_request_id=? WHERE external_item_id=?",
                               (command["request_id"], current["external_item_id"]))
                    db.execute("UPDATE listing_ops SET internal_action='OFFLINE' WHERE request_id=?",
                               (command["request_id"],))
                    item_id = current["external_item_id"]
                else:
                    item_id = None
            elif current and current["snapshot_json"] == snapshot_json:
                item_id = current["external_item_id"]
                db.execute("UPDATE provider_items SET last_request_id=? WHERE external_item_id=?",
                           (command["request_id"], item_id))
                db.execute("UPDATE listing_ops SET internal_action='NOOP' WHERE request_id=?", (command["request_id"],))
            elif current:
                item_id = current["external_item_id"]
                db.execute("INSERT OR IGNORE INTO provider_effects(request_id, action, target_id) VALUES (?, 'UPDATE', ?)",
                           (command["request_id"], item_id))
                db.execute("UPDATE provider_items SET snapshot_json=?, last_request_id=? WHERE external_item_id=?",
                           (snapshot_json, command["request_id"], item_id))
                db.execute("UPDATE listing_ops SET internal_action='UPDATE' WHERE request_id=?", (command["request_id"],))
                self._save_item_skus(db, command, item_id)
            else:
                prior_items = db.execute(
                    "SELECT COUNT(*) AS n FROM provider_items WHERE account_id=? AND listing_key=?",
                    (command["account_id"], command["listing_key"]),
                ).fetchone()["n"]
                item_id = f"item-{prior_items + 1}"
                db.execute("INSERT OR IGNORE INTO provider_effects(request_id, action, target_id) VALUES (?, 'PUBLISH', ?)",
                           (command["request_id"], item_id))
                db.execute(
                "INSERT INTO provider_items VALUES (?, ?, ?, 'CURRENT', ?, ?, ?)",
                    (item_id, command["account_id"], command["listing_key"], snapshot_json,
                     command["request_id"], command["request_id"]),
                )
                self._save_item_skus(db, command, item_id)
                db.execute("UPDATE listing_ops SET internal_action=? WHERE request_id=?",
                           ("REPUBLISH" if prior_items else "PUBLISH", command["request_id"]))

            if lose_response:
                # Provider state is durable, but the client sees only APPLYING;
                # Agent's reconciler must recover from its own persisted facts.
                db.commit()
                return self._observation(db.execute("SELECT * FROM listing_ops WHERE request_id=?",
                                                    (command["request_id"],)).fetchone())
            db.execute("UPDATE listing_ops SET state='SUCCEEDED', external_item_id=? WHERE request_id=?",
                       (item_id, command["request_id"]))
            return self._observation(db.execute("SELECT * FROM listing_ops WHERE request_id=?",
                                                (command["request_id"],)).fetchone())

    def _save_item_skus(self, db: sqlite3.Connection, command: dict[str, Any], item_id: str) -> None:
        snapshot = command["desired_snapshot"]
        for index, sku in enumerate(snapshot.get("skus", []) if snapshot is not None else []):
            external_sku_id = f"{item_id}-sku-{index + 1}"
            db.execute(
                "INSERT OR REPLACE INTO item_skus VALUES (?, ?, ?, ?, ?)",
                (command["account_id"], item_id, external_sku_id, sku["sku_key"], canonical(sku["spec_values"])),
            )

    def _reconcile_listing(self, db: sqlite3.Connection, operation: sqlite3.Row) -> None:
        if operation["desired_state"] == "OFFLINE":
            item = db.execute("SELECT * FROM provider_items WHERE last_request_id=?", (operation["request_id"],)).fetchone()
            if item and item["state"] == "OFFLINE":
                item_id = item["external_item_id"]
            else:
                current = db.execute(
                    "SELECT external_item_id FROM provider_items WHERE account_id=? AND listing_key=? AND state='CURRENT'",
                    (operation["account_id"], operation["listing_key"]),
                ).fetchone()
                item_id = current["external_item_id"] if current else None
        else:
            item = db.execute(
                "SELECT * FROM provider_items WHERE account_id=? AND listing_key=? AND state='CURRENT' AND snapshot_json=?",
                (operation["account_id"], operation["listing_key"], operation["snapshot_json"]),
            ).fetchone()
            if item is None:
                item = db.execute("SELECT * FROM provider_items WHERE last_request_id=?", (operation["request_id"],)).fetchone()
            item_id = item["external_item_id"] if item else None
        if item_id is not None or operation["desired_state"] == "OFFLINE":
            db.execute("UPDATE listing_ops SET state='SUCCEEDED', external_item_id=? WHERE request_id=?",
                       (item_id, operation["request_id"]))

    def observe_operation(self, request_id: str) -> dict[str, Any]:
        with self.db() as db:
            row = db.execute("SELECT * FROM listing_ops WHERE request_id=?", (request_id,)).fetchone()
            if row is None:
                raise KeyError(request_id)
            if row["state"] == "APPLYING":
                self._reconcile_listing(db, row)
                row = db.execute("SELECT * FROM listing_ops WHERE request_id=?", (request_id,)).fetchone()
            return self._observation(row)

    @staticmethod
    def _observation(row: sqlite3.Row) -> dict[str, Any]:
        return {"request_id": row["request_id"], "listing_key": row["listing_key"],
                "revision": row["revision"], "state": row["state"],
                "external_item_id": row["external_item_id"],
                "error": {"code": row["error_code"], "message": row["error_code"]} if row["error_code"] else None}

    def invalidate_current_item(self, account_id: str, listing_key: str) -> None:
        with self.db() as db:
            db.execute("UPDATE provider_items SET state='INVALID' WHERE account_id=? AND listing_key=? AND state='CURRENT'",
                       (account_id, listing_key))

    def current_item(self, account_id: str, listing_key: str) -> sqlite3.Row | None:
        with self.db() as db:
            return db.execute("SELECT * FROM provider_items WHERE account_id=? AND listing_key=? AND state='CURRENT'",
                              (account_id, listing_key)).fetchone()

    def write_count(self, action: str | None = None) -> int:
        with self.db() as db:
            if action:
                return db.execute("SELECT COUNT(*) FROM provider_effects WHERE action=?", (action,)).fetchone()[0]
            return db.execute("SELECT COUNT(*) FROM provider_effects").fetchone()[0]

    def count(self, sql: str, params: tuple[Any, ...] = ()) -> int:
        with self.db() as db:
            return db.execute(sql, params).fetchone()[0]

    def record_order(self, account_id: str, external_order_id: str, external_item_id: str,
                     external_sku_id: str, status: str = "pending_ship", quantity: int = 1,
                     amount: str = "10.00") -> dict[str, Any]:
        with self.db() as db:
            item = db.execute("SELECT * FROM provider_items WHERE account_id=? AND external_item_id=?",
                              (account_id, external_item_id)).fetchone()
            if item is None:
                raise KeyError(external_item_id)
            sku = db.execute("SELECT * FROM item_skus WHERE account_id=? AND external_item_id=? AND external_sku_id=?",
                             (account_id, external_item_id, external_sku_id)).fetchone()
            spec_values = json.loads(sku["spec_values_json"]) if sku else {}
            sku_key = sku["sku_key"] if sku else None
            db.execute(
                "INSERT INTO agent_orders VALUES (?, ?, ?, ?, ?, ?, 'CNY', ?, ?, NULL, '2026-10-02T00:00:00Z') "
                "ON CONFLICT(account_id, external_order_id) DO NOTHING",
                (account_id, external_order_id, external_item_id, status, quantity, amount,
                 external_sku_id, canonical(spec_values)),
            )
            return {"account_id": account_id, "external_order_id": external_order_id,
                    "listing_key": item["listing_key"], "external_item_id": external_item_id,
                    "external_status": status, "sku_key": sku_key,
                    "spec_values": spec_values, "quantity": quantity, "amount": amount,
                    "currency": "CNY", "placed_at": None, "updated_at": "2026-10-02T00:00:00Z"}

    def send_delivery(self, command: dict[str, Any], *, lose_response: bool = False) -> dict[str, str]:
        return self._delivery_operation("SEND", command, lose_response=lose_response)

    def confirm_delivery(self, command: dict[str, Any], *, lose_response: bool = False) -> dict[str, str]:
        return self._delivery_operation("CONFIRM", command, lose_response=lose_response)

    def _delivery_operation(self, operation_type: str, command: dict[str, Any], *, lose_response: bool) -> dict[str, str]:
        fingerprint = digest({key: value for key, value in command.items() if key != "request_id"})
        with self.db() as db:
            row = db.execute("SELECT * FROM delivery_ops WHERE request_id=?", (command["request_id"],)).fetchone()
            if row:
                if row["fingerprint"] != fingerprint or row["operation_type"] != operation_type:
                    raise IdempotencyConflict(command["request_id"])
                if row["state"] == "APPLYING":
                    self._reconcile_delivery(db, row)
                    row = db.execute("SELECT * FROM delivery_ops WHERE request_id=?", (command["request_id"],)).fetchone()
                return self._delivery_observation(command, row["state"])

            db.execute("INSERT INTO delivery_ops VALUES (?, ?, ?, ?, ?, 'APPLYING', NULL)",
                       (command["request_id"], operation_type, fingerprint,
                        command["account_id"], command["external_order_id"]))
            if operation_type == "SEND":
                db.execute("INSERT OR IGNORE INTO provider_effects(request_id, action, target_id) VALUES (?, 'SEND_MESSAGE', ?)",
                           (command["request_id"], command["external_order_id"]))
            else:
                db.execute("INSERT OR IGNORE INTO provider_effects(request_id, action, target_id) VALUES (?, 'CONFIRM_DELIVERY', ?)",
                           (command["request_id"], command["external_order_id"]))
                db.execute("UPDATE agent_orders SET status='shipped' WHERE account_id=? AND external_order_id=?",
                           (command["account_id"], command["external_order_id"]))
            if lose_response:
                db.commit()
                return self._delivery_observation(command, "APPLYING")
            db.execute("UPDATE delivery_ops SET state='SUCCEEDED' WHERE request_id=?", (command["request_id"],))
            return self._delivery_observation(command, "SUCCEEDED")

    @staticmethod
    def _delivery_observation(command: dict[str, Any], state: str) -> dict[str, Any]:
        result = {key: command[key] for key in ("request_id", "listing_key", "external_item_id", "external_order_id")}
        result["state"] = state
        if "unit_index" in command:
            result["unit_index"] = command["unit_index"]
        return result

    def _reconcile_delivery(self, db: sqlite3.Connection, operation: sqlite3.Row) -> None:
        action = "SEND_MESSAGE" if operation["operation_type"] == "SEND" else "CONFIRM_DELIVERY"
        effect = db.execute("SELECT 1 FROM provider_effects WHERE request_id=? AND action=?",
                            (operation["request_id"], action)).fetchone()
        if effect:
            db.execute("UPDATE delivery_ops SET state='SUCCEEDED' WHERE request_id=?",
                       (operation["request_id"],))

    def delivery_write_count(self, action: str) -> int:
        return self.write_count(action)


class FakeMyShop:
    """SQLite-backed business side: desired state, orders, and card ownership."""

    def __init__(self, path: Path):
        self.path = path
        with self.db() as db:
            db.executescript(
                """
                CREATE TABLE IF NOT EXISTS listings(
                    listing_key TEXT PRIMARY KEY, account_id TEXT NOT NULL,
                    revision INTEGER NOT NULL, desired_state TEXT NOT NULL,
                    snapshot_json TEXT, status TEXT NOT NULL,
                    external_item_id TEXT
                );
                CREATE TABLE IF NOT EXISTS adoption_markers(
                    listing_key TEXT PRIMARY KEY, external_item_id TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS listing_generations(
                    listing_key TEXT NOT NULL, external_item_id TEXT NOT NULL,
                    state TEXT NOT NULL, origin TEXT NOT NULL,
                    PRIMARY KEY(listing_key, external_item_id)
                );
                CREATE TABLE IF NOT EXISTS sku_mappings(
                    listing_key TEXT NOT NULL, sku_key TEXT NOT NULL,
                    spec_values_json TEXT NOT NULL,
                    PRIMARY KEY(listing_key, sku_key)
                );
                CREATE TABLE IF NOT EXISTS sales_orders(
                    account_id TEXT NOT NULL, external_order_id TEXT NOT NULL,
                    listing_key TEXT NOT NULL, external_item_id TEXT NOT NULL,
                    source_fingerprint TEXT NOT NULL, sku_key TEXT, status TEXT NOT NULL,
                    myshop_order_id TEXT, quantity INTEGER NOT NULL,
                    PRIMARY KEY(account_id, external_order_id)
                );
                CREATE TABLE IF NOT EXISTS myshop_orders(
                    id TEXT PRIMARY KEY, account_id TEXT NOT NULL,
                    external_order_id TEXT NOT NULL, sku_key TEXT NOT NULL,
                    status TEXT NOT NULL, quantity INTEGER NOT NULL,
                    UNIQUE(account_id, external_order_id)
                );
                CREATE TABLE IF NOT EXISTS card_keys(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, sku_key TEXT NOT NULL,
                    content TEXT NOT NULL UNIQUE, status TEXT NOT NULL DEFAULT 'AVAILABLE',
                    order_id TEXT
                );
                CREATE TABLE IF NOT EXISTS delivery_commands(
                    order_id TEXT NOT NULL, unit_index INTEGER NOT NULL,
                    request_id TEXT NOT NULL UNIQUE, card_key_id INTEGER NOT NULL UNIQUE,
                    content TEXT NOT NULL, status TEXT NOT NULL,
                    PRIMARY KEY(order_id, unit_index)
                );
                """
            )

    @contextmanager
    def db(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.path)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    @staticmethod
    def request_id(listing_key: str, revision: int) -> str:
        return f"ensure-{digest([listing_key, revision])[:32]}"

    def ensure_legacy_current_item(self, agent: FakeAgent, listing_key: str, account_id: str,
                                   revision: int, snapshot: dict[str, Any], external_item_id: str,
                                   *, lose_adoption_response: bool = False) -> tuple[dict[str, Any], dict[str, Any]]:
        adoption_request_id = f"adopt-{digest([listing_key, external_item_id])[:40]}"
        adoption_command = {
            "request_id": adoption_request_id, "listing_key": listing_key,
            "account_id": account_id, "external_item_id": external_item_id,
        }
        adoption = agent.adopt_existing_item(adoption_command, lose_response=lose_adoption_response)
        if adoption["state"] == "APPLYING":
            adoption = agent.adopt_existing_item(adoption_command)
        if adoption["state"] != "SUCCEEDED":
            return adoption, adoption
        with self.db() as db:
            db.execute("INSERT OR REPLACE INTO adoption_markers VALUES (?, ?)", (listing_key, external_item_id))
        ensure = self.ensure_listing(agent, listing_key, account_id, revision, "ONLINE", snapshot)
        return adoption, ensure

    def ensure_listing(self, agent: FakeAgent, listing_key: str, account_id: str,
                       revision: int, desired_state: str, snapshot: dict[str, Any] | None,
                       *, lose_response: bool = False) -> dict[str, Any]:
        with self.db() as db:
            current = db.execute("SELECT revision FROM listings WHERE listing_key=?", (listing_key,)).fetchone()
            if current and revision < current["revision"]:
                raise ValueError("STALE_REVISION")
            db.execute(
                "INSERT INTO listings VALUES (?, ?, ?, ?, ?, 'APPLYING', NULL) "
                "ON CONFLICT(listing_key) DO UPDATE SET account_id=excluded.account_id, revision=excluded.revision, "
                "desired_state=excluded.desired_state, snapshot_json=excluded.snapshot_json, status='APPLYING'",
                (listing_key, account_id, revision, desired_state,
                 canonical(snapshot) if snapshot is not None else None),
            )
            for sku in snapshot.get("skus", []) if snapshot is not None else []:
                db.execute("INSERT OR REPLACE INTO sku_mappings VALUES (?, ?, ?)",
                           (listing_key, sku["sku_key"], canonical(sku["spec_values"])))
        command = {"request_id": self.request_id(listing_key, revision), "listing_key": listing_key,
                   "account_id": account_id, "revision": revision, "desired_state": desired_state,
                   "desired_snapshot": snapshot}
        observation = agent.ensure_listing(command, lose_response=lose_response)
        self._apply_observation(listing_key, observation)
        return observation

    def observe(self, agent: FakeAgent, listing_key: str, revision: int) -> dict[str, Any]:
        observation = agent.observe_operation(self.request_id(listing_key, revision))
        self._apply_observation(listing_key, observation)
        return observation

    def _apply_observation(self, listing_key: str, observation: dict[str, Any]) -> None:
        status = {"APPLYING": "APPLYING", "SUCCEEDED": "ONLINE", "FAILED": "ERROR"}[observation["state"]]
        with self.db() as db:
            current_revision = db.execute("SELECT revision FROM listings WHERE listing_key=?", (listing_key,)).fetchone()
            if current_revision and observation["revision"] != current_revision["revision"]:
                return
            if observation["state"] == "SUCCEEDED":
                current = db.execute("SELECT desired_state FROM listings WHERE listing_key=?", (listing_key,)).fetchone()
                status = "OFFLINE" if current and current["desired_state"] == "OFFLINE" else "ONLINE"
                external_item_id = observation.get("external_item_id")
                if external_item_id is not None:
                    projected = db.execute(
                        "SELECT external_item_id FROM listing_generations WHERE listing_key=? AND state='CURRENT'",
                        (listing_key,),
                    ).fetchone()
                    if projected is None or projected["external_item_id"] != external_item_id:
                        db.execute("UPDATE listing_generations SET state='RETIRED' WHERE listing_key=? AND state='CURRENT'",
                                   (listing_key,))
                        db.execute("""INSERT INTO listing_generations VALUES (?, ?, 'CURRENT', 'CONVERGENCE')
                                      ON CONFLICT(listing_key, external_item_id) DO UPDATE
                                      SET state='CURRENT', origin='CONVERGENCE'""",
                                   (listing_key, external_item_id))
            db.execute("UPDATE listings SET status=?, external_item_id=COALESCE(?, external_item_id) WHERE listing_key=?",
                       (status, observation.get("external_item_id"), listing_key))

    def listing(self, listing_key: str) -> sqlite3.Row:
        with self.db() as db:
            return db.execute("SELECT * FROM listings WHERE listing_key=?", (listing_key,)).fetchone()

    def add_cards(self, sku_key: str, count: int) -> None:
        with self.db() as db:
            for n in range(count):
                db.execute("INSERT INTO card_keys(sku_key, content) VALUES (?, ?)",
                           (sku_key, f"{sku_key}-card-{n + 1}"))

    def import_order(self, event: dict[str, Any]) -> sqlite3.Row:
        if not event["external_item_id"]:
            raise ValueError("Agent order event must carry provider item identity")
        with self.db() as db:
            legacy = db.execute(
                """SELECT g.listing_key FROM listing_generations g
                   JOIN listings l ON l.listing_key=g.listing_key
                   WHERE l.account_id=? AND g.external_item_id=?
                   LIMIT 1""",
                (event["account_id"], event["external_item_id"]),
            ).fetchone()
        listing_key = event.get("listing_key")
        if listing_key:
            listing = self.listing(listing_key)
            if listing is None or listing["account_id"] != event["account_id"]:
                raise ValueError("listing key/account mismatch")
            if legacy is not None and legacy["listing_key"] != listing_key:
                raise ValueError("LISTING_MAPPING_CONFLICT")
        else:
            if legacy is None:
                raise ValueError("legacy external item identity is not linked")
            listing_key = legacy["listing_key"]
            listing = self.listing(listing_key)
        event_fp = digest(event)
        with self.db() as db:
            prior = db.execute("SELECT * FROM sales_orders WHERE account_id=? AND external_order_id=?",
                               (event["account_id"], event["external_order_id"])).fetchone()
            if prior:
                if prior["source_fingerprint"] != event_fp:
                    db.execute("UPDATE sales_orders SET status='SOURCE_CONFLICT' WHERE account_id=? AND external_order_id=?",
                               (event["account_id"], event["external_order_id"]))
                return db.execute("SELECT * FROM sales_orders WHERE account_id=? AND external_order_id=?",
                                  (event["account_id"], event["external_order_id"])).fetchone()

            exact_mapping = db.execute("SELECT sku_key FROM sku_mappings WHERE listing_key=? AND spec_values_json=?",
                                       (listing_key, canonical(event.get("spec_values") or {}))).fetchone()
            by_key = db.execute("SELECT sku_key FROM sku_mappings WHERE listing_key=? AND sku_key=?",
                                (listing_key, event.get("sku_key"))).fetchone() if event.get("sku_key") else None
            mapped = by_key or exact_mapping
            if event.get("sku_key") and by_key is None:
                mapped = None
            if mapped is None:
                db.execute("INSERT INTO sales_orders VALUES (?, ?, ?, ?, ?, NULL, 'MAPPING_ERROR', NULL, ?)",
                           (event["account_id"], event["external_order_id"], listing_key,
                            event["external_item_id"], event_fp, event["quantity"]))
                return db.execute("SELECT * FROM sales_orders WHERE account_id=? AND external_order_id=?",
                                  (event["account_id"], event["external_order_id"])).fetchone()

            order_id = f"order-{event['external_order_id']}"
            db.execute("INSERT INTO myshop_orders VALUES (?, ?, ?, ?, 'PAID', ?)",
                       (order_id, event["account_id"], event["external_order_id"], mapped["sku_key"], event["quantity"]))
            db.execute("INSERT INTO sales_orders VALUES (?, ?, ?, ?, ?, ?, 'IMPORTED', ?, ?)",
                       (event["account_id"], event["external_order_id"], listing_key,
                        event["external_item_id"], event_fp, mapped["sku_key"], order_id, event["quantity"]))
            return db.execute("SELECT * FROM sales_orders WHERE account_id=? AND external_order_id=?",
                              (event["account_id"], event["external_order_id"])).fetchone()

    def fulfill_order(self, order_id: str) -> bool:
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            order = db.execute("SELECT * FROM myshop_orders WHERE id=?", (order_id,)).fetchone()
            if order is None:
                return False
            if order["status"] == "DELIVERED":
                return True
            cards = db.execute("SELECT * FROM card_keys WHERE sku_key=? AND status='AVAILABLE' ORDER BY id LIMIT ?",
                               (order["sku_key"], order["quantity"])).fetchall()
            if len(cards) != order["quantity"]:
                return False
            for card in cards:
                db.execute("UPDATE card_keys SET status='SOLD', order_id=? WHERE id=? AND status='AVAILABLE'",
                           (order_id, card["id"]))
            db.execute("UPDATE myshop_orders SET status='DELIVERED' WHERE id=?", (order_id,))
            return True

    def plan_delivery(self, order_id: str) -> list[sqlite3.Row]:
        with self.db() as db:
            order = db.execute("SELECT * FROM myshop_orders WHERE id=?", (order_id,)).fetchone()
            if order is None or order["status"] != "DELIVERED":
                return []
            sold_cards = db.execute("SELECT * FROM card_keys WHERE order_id=? AND status='SOLD' ORDER BY id",
                                    (order_id,)).fetchall()
            for unit, card in enumerate(sold_cards):
                request_id = f"delivery:{order['external_order_id']}:{unit}"
                db.execute("INSERT OR IGNORE INTO delivery_commands VALUES (?, ?, ?, ?, ?, 'PENDING')",
                           (order_id, unit, request_id, card["id"], card["content"]))
            return db.execute("SELECT * FROM delivery_commands WHERE order_id=? ORDER BY unit_index",
                              (order_id,)).fetchall()

    def send_delivery(self, agent: FakeAgent, order_id: str, *, lose_response: bool = False) -> list[dict[str, str]]:
        results = []
        for command in self.plan_delivery(order_id):
            my_order = self._order(order_id)
            envelope = {"request_id": command["request_id"], "listing_key": self._listing_key(my_order["account_id"], order_id),
                        "account_id": my_order["account_id"], "external_item_id": self._external_item_id(my_order["account_id"], order_id),
                        "external_order_id": my_order["external_order_id"], "unit_index": command["unit_index"],
                        "content_parts": [command["content"]]}
            result = agent.send_delivery(envelope, lose_response=lose_response)
            with self.db() as db:
                db.execute("UPDATE delivery_commands SET status=? WHERE request_id=?",
                           (result["state"], command["request_id"]))
            results.append(result)
        return results

    def _order(self, order_id: str) -> sqlite3.Row:
        with self.db() as db:
            return db.execute("SELECT * FROM myshop_orders WHERE id=?", (order_id,)).fetchone()

    def _listing_key(self, account_id: str, order_id: str) -> str:
        with self.db() as db:
            return db.execute("SELECT listing_key FROM sales_orders WHERE account_id=? AND myshop_order_id=?",
                              (account_id, order_id)).fetchone()[0]

    def _external_item_id(self, account_id: str, order_id: str) -> str:
        with self.db() as db:
            return db.execute("SELECT external_item_id FROM sales_orders WHERE account_id=? AND myshop_order_id=?",
                              (account_id, order_id)).fetchone()[0]

    def counts(self, sql: str, params: tuple[Any, ...] = ()) -> int:
        with self.db() as db:
            return db.execute(sql, params).fetchone()[0]

    def count(self, sql: str, params: tuple[Any, ...] = ()) -> int:
        return self.counts(sql, params)


class GoldenFlowTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="myshop-xianyu-golden-")
        root = Path(self.temp.name)
        self.agent_db = root / "agent.sqlite"
        self.myshop_db = root / "myshop.sqlite"
        self.agent = FakeAgent(self.agent_db)
        self.myshop = FakeMyShop(self.myshop_db)
        self.account = "account-1"
        self.listing_key = "listing-1"
        self.agent.set_auth(self.account, "VALID")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def ensure(self, revision: int = 1, state: str = "ONLINE", snapshot: dict[str, Any] | None = None,
               *, lose_response: bool = False) -> dict[str, Any]:
        desired_snapshot = None if state == "OFFLINE" else (snapshot if snapshot is not None else SNAPSHOT)
        return self.myshop.ensure_listing(self.agent, self.listing_key, self.account, revision, state,
                                          desired_snapshot, lose_response=lose_response)

    def online_item(self, revision: int = 1) -> str:
        result = self.ensure(revision)
        self.assertEqual("SUCCEEDED", result["state"])
        return result["external_item_id"]

    def create_order(self, *, external_order_id: str = "order-1", sku_index: int = 0,
                     external_item_id: str | None = None) -> dict[str, Any]:
        item_id = external_item_id or self.online_item()
        with self.agent.db() as db:
            sku_id = db.execute("SELECT external_sku_id FROM item_skus WHERE external_item_id=? ORDER BY external_sku_id LIMIT 1 OFFSET ?",
                                (item_id, sku_index)).fetchone()[0]
        return self.agent.record_order(self.account, external_order_id, item_id, sku_id)

    def import_and_fulfill(self, event: dict[str, Any], count: int = 3) -> str:
        self.myshop.add_cards(event["sku_key"], count)
        row = self.myshop.import_order(event)
        self.assertEqual("IMPORTED", row["status"])
        self.assertTrue(self.myshop.fulfill_order(row["myshop_order_id"]))
        return row["myshop_order_id"]

    def test_01_new_listing_online_is_projected_from_agent_success(self):
        result = self.ensure()
        item = self.agent.current_item(self.account, self.listing_key)
        self.assertEqual("ONLINE", self.myshop.listing(self.listing_key)["status"])
        self.assertEqual("SUCCEEDED", result["state"])
        self.assertEqual(result["external_item_id"], item["external_item_id"])
        generation = self.myshop.counts(
            "SELECT COUNT(*) FROM listing_generations WHERE listing_key=? "
            "AND external_item_id=? AND state='CURRENT' AND origin='CONVERGENCE'",
            (self.listing_key, result["external_item_id"]),
        )
        self.assertEqual(1, generation)
        self.assertEqual(1, self.agent.write_count("PUBLISH"))

    def test_02_price_change_converges_to_provider_snapshot(self):
        self.online_item()
        changed = json.loads(canonical(SNAPSHOT))
        changed["price"] = "12.50"
        changed["skus"][0]["price"] = "12.50"
        changed["skus"][1]["price"] = "13.00"
        result = self.ensure(2, snapshot=changed)
        provider = self.agent.current_item(self.account, self.listing_key)
        self.assertEqual("SUCCEEDED", result["state"])
        self.assertEqual(changed, json.loads(provider["snapshot_json"]))
        self.assertEqual(1, self.agent.write_count("UPDATE"))

    def test_03_stock_change_converges_to_provider_snapshot(self):
        self.online_item()
        changed = json.loads(canonical(SNAPSHOT))
        changed["stock"] = 1
        changed["skus"][0]["stock"] = 1
        changed["skus"][1]["stock"] = 0
        result = self.ensure(2, snapshot=changed)
        provider = self.agent.current_item(self.account, self.listing_key)
        self.assertEqual("SUCCEEDED", result["state"])
        self.assertEqual(changed, json.loads(provider["snapshot_json"]))
        self.assertEqual(1, self.agent.write_count("UPDATE"))

    def test_04_offline_converges_to_provider_item_state(self):
        item_id = self.online_item()
        result = self.ensure(2, state="OFFLINE")
        with self.agent.db() as db:
            provider_state = db.execute("SELECT state FROM provider_items WHERE external_item_id=?", (item_id,)).fetchone()[0]
            offline_command = db.execute(
                "SELECT snapshot_json FROM listing_ops WHERE request_id=?", (result["request_id"],)
            ).fetchone()
        self.assertEqual("OFFLINE", provider_state)
        self.assertEqual("OFFLINE", self.myshop.listing(self.listing_key)["status"])
        self.assertIsNone(offline_command["snapshot_json"])
        self.assertEqual("SUCCEEDED", result["state"])

    def test_05_expired_provider_item_is_replaced_inside_agent(self):
        old_id = self.online_item()
        self.agent.invalidate_current_item(self.account, self.listing_key)
        result = self.ensure(2)
        self.assertNotEqual(old_id, result["external_item_id"])
        self.assertEqual("ONLINE", self.myshop.listing(self.listing_key)["status"])
        self.assertNotIn("REPUBLISH", result)
        self.assertEqual(2, self.agent.write_count("PUBLISH"))
        with self.agent.db() as db:
            self.assertEqual("INVALID", db.execute("SELECT state FROM provider_items WHERE external_item_id=?", (old_id,)).fetchone()[0])
        with self.myshop.db() as db:
            generations = db.execute(
                "SELECT external_item_id, state, origin FROM listing_generations WHERE listing_key=?",
                (self.listing_key,),
            ).fetchall()
        self.assertEqual({(old_id, "RETIRED", "CONVERGENCE"),
                          (result["external_item_id"], "CURRENT", "CONVERGENCE")},
                         {(row["external_item_id"], row["state"], row["origin"]) for row in generations})
        self.assertNotIn("CANDIDATE", {row["state"] for row in generations})

    def test_05b_legacy_current_item_is_adopted_before_ensure_without_provider_write(self):
        item_id = "legacy-item-A"
        legacy_listing_key = "pre-convergence-listing"
        with self.agent.db() as db:
            db.execute("INSERT INTO managed_items VALUES (?, ?, 'MYSHOP')", (self.account, item_id))
            db.execute("INSERT INTO provider_items VALUES (?, ?, ?, 'CURRENT', ?, 'legacy-request', 'legacy-request')",
                       (item_id, self.account, legacy_listing_key, canonical(SNAPSHOT)))
        with self.myshop.db() as db:
            db.execute("INSERT INTO listings VALUES (?, ?, 1, 'ONLINE', ?, 'ONLINE', ?)",
                       (self.listing_key, self.account, canonical(SNAPSHOT), item_id))
            db.execute("INSERT INTO listing_generations VALUES (?, ?, 'CURRENT', 'LEGACY')",
                       (self.listing_key, item_id))

        adoption, ensure = self.myshop.ensure_legacy_current_item(
            self.agent, self.listing_key, self.account, 1, SNAPSHOT, item_id,
            lose_adoption_response=True,
        )

        self.assertEqual("SUCCEEDED", adoption["state"])
        self.assertEqual(item_id, adoption["external_item_id"])
        self.assertEqual("SUCCEEDED", ensure["state"])
        self.assertEqual(item_id, ensure["external_item_id"])
        self.assertEqual(0, self.agent.write_count())
        with self.myshop.db() as db:
            marker = db.execute(
                "SELECT external_item_id FROM adoption_markers WHERE listing_key=?", (self.listing_key,)
            ).fetchone()
        self.assertEqual(item_id, marker["external_item_id"])
        with self.agent.db() as db:
            self.assertEqual(1, db.execute("SELECT COUNT(*) FROM adoption_ops").fetchone()[0])
            self.assertEqual("CURRENT", db.execute(
                "SELECT state FROM listing_bindings WHERE account_id=? AND listing_key=? AND external_item_id=?",
                (self.account, self.listing_key, item_id),
            ).fetchone()[0])

    def test_06_same_revision_is_idempotent(self):
        first = self.ensure()
        second = self.ensure()
        self.assertEqual(first, second)
        self.assertEqual(1, self.agent.write_count("PUBLISH"))
        self.assertEqual(1, self.agent.count("SELECT COUNT(*) FROM listing_ops"))

    def test_07_provider_success_with_lost_http_result_recovers_by_same_post_replay(self):
        first = self.ensure(lose_response=True)
        self.assertEqual("APPLYING", first["state"])
        request_id = first["request_id"]
        self.assertEqual(1, self.agent.write_count("PUBLISH"))
        self.agent = FakeAgent(self.agent_db)
        replay = self.ensure()
        self.assertEqual(request_id, replay["request_id"])
        self.assertEqual("SUCCEEDED", replay["state"])
        self.assertEqual("ONLINE", self.myshop.listing(self.listing_key)["status"])
        self.assertEqual(1, self.agent.write_count("PUBLISH"))

    def test_07b_noop_success_with_lost_http_result_recovers_by_same_post_replay(self):
        self.online_item(revision=1)
        applying = self.ensure(revision=2, lose_response=True)
        self.assertEqual("APPLYING", applying["state"])
        self.assertEqual("NOOP", self.agent.count(
            "SELECT internal_action FROM listing_ops WHERE revision=2"
        ))

        replay = self.ensure(revision=2)
        self.assertEqual(applying["request_id"], replay["request_id"])
        self.assertEqual("SUCCEEDED", replay["state"])
        self.assertEqual("ONLINE", self.myshop.listing(self.listing_key)["status"])
        self.assertEqual(1, self.agent.write_count("PUBLISH"))
        self.assertEqual(0, self.agent.write_count("UPDATE"))

    def test_07c_same_revision_changed_snapshot_conflicts(self):
        self.ensure(revision=1)
        changed = json.loads(canonical(SNAPSHOT))
        changed["price"] = "11.00"
        changed["skus"][0]["price"] = "11.00"
        changed["skus"][1]["price"] = "11.50"
        with self.assertRaises(IdempotencyConflict):
            self.ensure(revision=1, snapshot=changed)
        self.assertEqual(1, self.agent.write_count("PUBLISH"))
        self.assertEqual(0, self.agent.write_count("UPDATE"))

    def test_08_paid_agent_order_imports_with_listing_and_item_identity(self):
        event = self.create_order()
        self.assertEqual(self.listing_key, event["listing_key"])
        self.assertTrue(event["external_item_id"])
        with self.myshop.db() as db:
            db.execute("DELETE FROM listing_generations WHERE listing_key=?", (self.listing_key,))
        imported = self.myshop.import_order(event)
        self.assertEqual("IMPORTED", imported["status"])
        self.assertEqual(event["external_item_id"], imported["external_item_id"])

    def test_09_sku_mapping_is_exact(self):
        event = self.create_order(sku_index=0)
        accepted = self.myshop.import_order(event)
        self.assertEqual("sku-black", accepted["sku_key"])
        legacy = dict(event)
        legacy["external_order_id"] = "order-legacy-sku"
        legacy.pop("listing_key")
        legacy["sku_key"] = None
        accepted_legacy = self.myshop.import_order(legacy)
        self.assertEqual("IMPORTED", accepted_legacy["status"])
        self.assertEqual("sku-black", accepted_legacy["sku_key"])
        fuzzy = dict(event)
        fuzzy["external_order_id"] = "order-fuzzy"
        fuzzy["sku_key"] = None
        fuzzy["spec_values"] = {"Type": "Month"}
        rejected = self.myshop.import_order(fuzzy)
        self.assertEqual("MAPPING_ERROR", rejected["status"])
        self.assertEqual(0, self.myshop.counts("SELECT COUNT(*) FROM myshop_orders WHERE external_order_id='order-fuzzy'"))

    def test_10_duplicate_external_order_event_creates_one_myshop_order(self):
        event = self.create_order()
        first = self.myshop.import_order(event)
        second = self.myshop.import_order(event)
        self.assertEqual(first["myshop_order_id"], second["myshop_order_id"])
        self.assertEqual(1, self.myshop.count("SELECT COUNT(*) FROM sales_orders WHERE external_order_id='order-1'"))
        self.assertEqual(1, self.myshop.count("SELECT COUNT(*) FROM myshop_orders WHERE external_order_id='order-1'"))

    def test_11_card_key_is_allocated_once(self):
        event = self.create_order()
        order_id = self.import_and_fulfill(event, count=3)
        self.assertTrue(self.myshop.fulfill_order(order_id))
        self.assertEqual(1, self.myshop.count("SELECT COUNT(*) FROM card_keys WHERE order_id=? AND status='SOLD'", (order_id,)))
        self.assertEqual(1, self.myshop.count("SELECT COUNT(*) FROM myshop_orders WHERE id=? AND status='DELIVERED'", (order_id,)))

    def test_12_send_timeout_keeps_same_card_and_agent_request(self):
        event = self.create_order()
        order_id = self.import_and_fulfill(event, count=1)
        first = self.myshop.send_delivery(self.agent, order_id, lose_response=True)
        self.assertEqual("APPLYING", first[0]["state"])
        self.assertEqual(1, self.myshop.count("SELECT COUNT(*) FROM card_keys WHERE order_id=? AND status='SOLD'", (order_id,)))
        self.agent = FakeAgent(self.agent_db)
        second = self.myshop.send_delivery(self.agent, order_id)
        self.assertEqual("SUCCEEDED", second[0]["state"])
        self.assertEqual(1, self.agent.delivery_write_count("SEND_MESSAGE"))
        self.assertEqual(1, self.myshop.count("SELECT COUNT(*) FROM delivery_commands WHERE order_id=?", (order_id,)))

    def test_13_confirm_timeout_converges_by_agent_reconciliation(self):
        event = self.create_order()
        self.import_and_fulfill(event, count=1)
        command = {"request_id": "confirm:order-1", "listing_key": self.listing_key,
                   "account_id": self.account, "external_item_id": event["external_item_id"],
                   "external_order_id": event["external_order_id"]}
        first = self.agent.confirm_delivery(command, lose_response=True)
        self.assertEqual("APPLYING", first["state"])
        self.agent = FakeAgent(self.agent_db)
        second = self.agent.confirm_delivery(command)
        self.assertEqual("SUCCEEDED", second["state"])
        self.assertEqual(1, self.agent.delivery_write_count("CONFIRM_DELIVERY"))

    def test_14_myshop_restart_reuses_revision_and_request_id(self):
        self.ensure()
        self.myshop = FakeMyShop(self.myshop_db)
        observation = self.myshop.ensure_listing(self.agent, self.listing_key, self.account, 1, "ONLINE", SNAPSHOT)
        self.assertEqual("SUCCEEDED", observation["state"])
        self.assertEqual(1, self.agent.write_count("PUBLISH"))
        self.assertEqual("ONLINE", self.myshop.listing(self.listing_key)["status"])

    def test_15_agent_restart_recovers_ledger_without_another_provider_write(self):
        self.ensure(lose_response=True)
        self.agent = FakeAgent(self.agent_db)
        self.myshop = FakeMyShop(self.myshop_db)
        observation = self.myshop.ensure_listing(self.agent, self.listing_key, self.account,
                                                 1, "ONLINE", SNAPSHOT)
        self.assertEqual("SUCCEEDED", observation["state"])
        self.assertEqual(1, self.agent.write_count("PUBLISH"))

    def test_16_auth_expiry_fails_closed_without_provider_retry_storm(self):
        self.agent.set_auth(self.account, "AUTH_REQUIRED")
        first = self.ensure()
        for _ in range(20):
            repeated = self.ensure()
        self.assertEqual("FAILED", first["state"])
        self.assertEqual("AUTH_REQUIRED", first["error"]["code"])
        self.assertEqual("FAILED", repeated["state"])
        self.assertEqual(0, self.agent.write_count())
        self.assertEqual("ERROR", self.myshop.listing(self.listing_key)["status"])

    def test_17_same_request_id_different_fingerprint_conflicts_without_provider_write(self):
        self.ensure()
        changed = json.loads(canonical(SNAPSHOT))
        changed["price"] = "11.00"
        changed["skus"][0]["price"] = "11.00"
        changed["skus"][1]["price"] = "11.50"
        before = self.agent.write_count()
        with self.assertRaises(IdempotencyConflict):
            self.ensure(snapshot=changed)
        self.assertEqual(before, self.agent.write_count())

    def test_18_delivery_observation_and_ledger_never_contain_card_content(self):
        event = self.create_order()
        order_id = self.import_and_fulfill(event, count=1)
        results = self.myshop.send_delivery(self.agent, order_id)
        with self.myshop.db() as db:
            content = db.execute("SELECT content FROM delivery_commands WHERE order_id=?", (order_id,)).fetchone()[0]
        self.assertNotIn(content, canonical(results))
        with self.agent.db() as db:
            rows = [dict(row) for row in db.execute("SELECT * FROM delivery_ops")]
        self.assertNotIn(content, canonical(rows))
        self.assertNotIn("content_parts", canonical(rows))
        self.assertEqual({"request_id", "listing_key", "external_item_id", "external_order_id", "unit_index", "state"}, set(results[0]))

    def test_19_stale_revision_and_observation_cannot_overwrite_newer_desired_state(self):
        self.ensure(revision=2)
        changed = json.loads(canonical(SNAPSHOT))
        changed["price"] = "12.00"
        changed["skus"][0]["price"] = "12.00"
        changed["skus"][1]["price"] = "12.50"
        self.ensure(revision=3, snapshot=changed)
        before = self.agent.write_count()
        stale = self.agent.ensure_listing({"request_id": "ensure:listing-1:1", "listing_key": self.listing_key,
                "account_id": self.account, "revision": 1, "desired_state": "OFFLINE", "desired_snapshot": None})
        self.assertEqual("FAILED", stale["state"])
        self.assertEqual("STALE_REVISION", stale["error"]["code"])
        self.assertEqual(before, self.agent.write_count())
        self.myshop.observe(self.agent, self.listing_key, 2)
        self.assertEqual(3, self.myshop.listing(self.listing_key)["revision"])
        self.assertEqual("12.00", json.loads(self.agent.current_item(self.account, self.listing_key)["snapshot_json"])["price"])
        with self.assertRaisesRegex(ValueError, "STALE_REVISION"):
            self.ensure(revision=1)


if __name__ == "__main__":
    unittest.main(verbosity=2)

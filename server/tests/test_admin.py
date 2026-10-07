import json
import os
import stat
from urllib.parse import parse_qs, urlsplit

import pytest

from agentdeck import admin
from agentdeck import config as cfgmod
from agentdeck.auth import DeviceStore
from agentdeck.downloads import DownloadTokens


def test_pair_json_uri_is_consumable(env, capsys):
    assert admin.main(["pair", "--server", "https://mac.tail0.ts.net:8443", "--json"]) == 0
    out = json.loads(capsys.readouterr().out)
    uri = urlsplit(out["uri"])
    assert uri.scheme == "agentdeck" and uri.netloc == "pair"
    q = parse_qs(uri.query)
    assert q["server"] == ["https://mac.tail0.ts.net:8443"]
    store = DeviceStore(env["home"])
    assert store.consume_pairing_code(q["code"][0])
    assert not store.consume_pairing_code(q["code"][0])


def test_pair_requires_https_base(env, monkeypatch):
    monkeypatch.setattr(cfgmod, "detect_tailscale_base_url", lambda port=None: None)
    with pytest.raises(SystemExit):
        admin.main(["pair", "--json"])
    with pytest.raises(SystemExit):
        admin.main(["pair", "--server", "http://insecure:18787", "--json"])


def test_pair_qr_svg_is_private(env, capsys):
    svg = env["tmp"] / "qr.svg"
    admin.main(["pair", "--server", "https://mac.tail0.ts.net:8443", "--svg", str(svg)])
    assert svg.read_text().startswith("<?xml") or "<svg" in svg.read_text()
    assert stat.S_IMODE(os.stat(svg).st_mode) == 0o600
    out = capsys.readouterr().out
    assert "Code:" in out


def test_set_public_url_and_apk_link(env, capsys):
    admin.main(["set-public-url", "https://mac.tail0.ts.net:8443/"])
    assert cfgmod.load_server_config().public_base_url == "https://mac.tail0.ts.net:8443"
    with pytest.raises(SystemExit):
        admin.main(["set-public-url", "https://mac.tail0.ts.net:8443/path"])
    admin.main(["apk-link"])
    url = capsys.readouterr().out.strip().splitlines()[-1]
    assert url.startswith("https://mac.tail0.ts.net:8443/download/")
    assert DownloadTokens(env["home"]).valid(url.rsplit("/", 1)[1])


def test_devices_and_revoke(env, capsys):
    store = DeviceStore(env["home"])
    code, _ = store.create_pairing_code()
    creds = store.pair(code, "Fold", None)
    admin.main(["devices"])
    out = capsys.readouterr().out
    assert creds["deviceId"] in out and creds["token"] not in out and creds["pushKey"] not in out
    assert admin.main(["revoke", creds["deviceId"]]) == 0
    assert admin.main(["revoke", creds["deviceId"]]) == 1


def test_local_token_created_private(env):
    tok = cfgmod.read_local_token()
    assert len(tok) >= 32 and cfgmod.read_local_token() == tok
    assert stat.S_IMODE(os.stat(env["home"] / "local-token").st_mode) == 0o600

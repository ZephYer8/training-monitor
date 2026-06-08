from __future__ import annotations

import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

ALLOWED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
}

FORBIDDEN_PERMISSIONS = {
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.CAMERA",
    "android.permission.RECORD_AUDIO",
    "android.permission.READ_CONTACTS",
    "android.permission.WRITE_CONTACTS",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_AUDIO",
}


def fail(message: str) -> None:
    print(f"[audit] FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def ok(message: str) -> None:
    print(f"[audit] OK: {message}")


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def attr(node: ET.Element, name: str) -> str:
    return node.attrib.get(f"{ANDROID_NS}{name}", "")


def audit_manifest() -> None:
    manifest = ET.parse(ROOT / "android/app/src/main/AndroidManifest.xml").getroot()
    permissions = {
        attr(item, "name")
        for item in manifest.findall("uses-permission")
        if attr(item, "name")
    }
    extra = permissions - ALLOWED_PERMISSIONS
    forbidden = permissions & FORBIDDEN_PERMISSIONS
    if extra:
        fail(f"unexpected Android permissions: {sorted(extra)}")
    if forbidden:
        fail(f"forbidden Android permissions: {sorted(forbidden)}")

    application = manifest.find("application")
    if application is None:
        fail("missing application node")
    if attr(application, "allowBackup") != "false":
        fail("android:allowBackup must be false")
    if attr(application, "fullBackupContent") != "false":
        fail("android:fullBackupContent must be false")
    if attr(application, "dataExtractionRules") != "@xml/data_extraction_rules":
        fail("data extraction rules must be configured")
    if attr(application, "usesCleartextTraffic") != "${usesCleartextTraffic}":
        fail("usesCleartextTraffic must be controlled by build type")

    services = manifest.findall("application/service")
    if not services or any(attr(service, "exported") != "false" for service in services):
        fail("background services must be non-exported")

    ok("Android manifest permissions and backup settings")


def audit_backup_rules() -> None:
    rules = text("android/app/src/main/res/xml/data_extraction_rules.xml")
    for domain in ("file", "database", "sharedpref"):
        if f'<exclude domain="{domain}" path="."' not in rules:
            fail(f"data extraction rules must exclude {domain}")
    ok("Android data extraction rules")


def audit_gradle() -> None:
    gradle = text("android/app/build.gradle.kts")
    required = {
        'applicationId = "com.modeltest.monitor"': "stable application id",
        "targetSdk = 35": "targetSdk 35",
        'manifestPlaceholders["usesCleartextTraffic"] = "false"': "release disables cleartext",
        'manifestPlaceholders["usesCleartextTraffic"] = "true"': "debug permits local cleartext",
    }
    for needle, label in required.items():
        if needle not in gradle:
            fail(f"missing Gradle setting: {label}")
    ok("Android Gradle release/debug settings")


def audit_release_workflow() -> None:
    workflow = text(".github/workflows/android-apk.yml")
    required = {
        "APP_PRIVACY_POLICY_URL": "privacy policy metadata",
        "APP_CONTACT_EMAIL": "contact metadata",
        "APP_BEIAN_ID": "beian metadata",
        "MARKET_READY": "market readiness flag",
        "MARKET_READY_NOTE": "market readiness note",
        "market_ready=$MARKET_READY": "build info market readiness is computed",
        "privacy_policy_url=${APP_PRIVACY_POLICY_URL:-}": "build info privacy policy url",
        "contact_email=${APP_CONTACT_EMAIL:-}": "build info contact email",
        "app_beian_id=${APP_BEIAN_ID:-}": "build info beian id",
    }
    for needle, label in required.items():
        if needle not in workflow:
            fail(f"missing release workflow setting: {label}")
    if 'echo "market_ready=true"' in workflow:
        fail("release workflow must not hard-code market_ready=true")
    ok("Android release workflow market readiness metadata")


def version_from_gradle() -> str:
    match = re.search(r'versionName\s*=\s*"([^"]+)"', text("android/app/build.gradle.kts"))
    if not match:
        fail("cannot read Android versionName")
    return match.group(1)


def version_from_pyproject() -> str:
    match = re.search(r'^version\s*=\s*"([^"]+)"', text("pyproject.toml"), re.M)
    if not match:
        fail("cannot read Python package version")
    return match.group(1)


def audit_versions() -> None:
    android_version = version_from_gradle()
    python_version = version_from_pyproject()
    if android_version != python_version:
        fail(f"version mismatch: Android {android_version}, Python {python_version}")
    ok(f"Android and Python package versions match: {android_version}")


def audit_branding_and_copy() -> None:
    strings = text("android/app/src/main/res/values/strings.xml")
    if "<string name=\"app_name\">训迹</string>" not in strings:
        fail("app name must stay as 训迹")

    checked_files = {
        "android/app/src/main/java/com/modeltest/monitor/MainActivity.kt": text("android/app/src/main/java/com/modeltest/monitor/MainActivity.kt"),
        "README.md": text("README.md"),
        "ui-preview/index.html": text("ui-preview/index.html"),
    }
    forbidden_terms = ("荣耀", "华为", "灵动", "胶囊", "Honor", "Huawei", "HarmonyOS")
    for path, content in checked_files.items():
        for term in forbidden_terms:
            if term in content:
                fail(f"device/vendor-specific copy found in {path}: {term}")

    main = checked_files["android/app/src/main/java/com/modeltest/monitor/MainActivity.kt"]
    if "SettingsCard(title = \"关于我们\"" not in main:
        fail("settings page must expose 关于我们 instead of test-device notes")
    for internal_copy in ("后期申请", "软著", "上架时"):
        if internal_copy in main:
            fail(f"internal release-planning copy must not appear in App UI: {internal_copy}")
    for required_copy in ("项目主页：github.com/ZephYer8/training-monitor", "反馈渠道：GitHub Issues 或应用市场反馈入口"):
        if required_copy not in main:
            fail(f"about-us page missing user-facing contact copy: {required_copy}")
    ok("app branding and generic user-facing copy")


def audit_icon_assets() -> None:
    foreground_path = ROOT / "android/app/src/main/res/drawable/ic_launcher_foreground.xml"
    background_path = ROOT / "android/app/src/main/res/drawable/ic_launcher_background.xml"
    notification_path = ROOT / "android/app/src/main/res/drawable/ic_notification.xml"
    for icon_path in (foreground_path, background_path, notification_path):
        if not icon_path.exists():
            fail(f"missing icon asset: {icon_path.relative_to(ROOT)}")
        ET.parse(icon_path)

    foreground = foreground_path.read_text(encoding="utf-8")
    for color in ("#0F172A", "#2563EB", "#22C55E"):
        if color not in foreground:
            fail(f"launcher foreground should include dashboard brand color {color}")

    main = text("android/app/src/main/java/com/modeltest/monitor/MainActivity.kt")
    if "setSmallIcon(R.drawable.ic_notification)" not in main:
        fail("notifications must use the dedicated notification icon")
    ok("launcher and notification icon assets")


def audit_server_security() -> None:
    app = text("server/app.py")
    required = [
        "import hmac",
        "hmac.compare_digest",
        '@app.get("/api/status", dependencies=[Depends(require_token)])',
        '@app.post("/api/status", dependencies=[Depends(require_token)])',
        '@app.post("/api/reset", dependencies=[Depends(require_token)])',
    ]
    for needle in required:
        if needle not in app:
            fail(f"server token guard missing: {needle}")
    ok("server API token checks")


def audit_privacy_text() -> None:
    main = text("android/app/src/main/java/com/modeltest/monitor/MainActivity.kt")
    readme = text("README.md")
    for needle in ("隐私与权限提示", "清除 Token", "撤回同意并停止使用"):
        if needle not in main:
            fail(f"missing app privacy control text: {needle}")
    for needle in ("个人信息保护法", "App违法违规收集使用个人信息行为认定方法", "隐私政策要点模板"):
        if needle not in readme:
            fail(f"missing compliance note in README: {needle}")
    ok("privacy controls and compliance notes")


def run_openmmlab_parser_test() -> None:
    subprocess.run(
        [sys.executable, str(ROOT / "server/test_openmmlab_parser.py")],
        cwd=ROOT,
        check=True,
    )
    ok("OpenMMLab parser test")


def main() -> None:
    audit_manifest()
    audit_backup_rules()
    audit_gradle()
    audit_release_workflow()
    audit_versions()
    audit_branding_and_copy()
    audit_icon_assets()
    audit_server_security()
    audit_privacy_text()
    run_openmmlab_parser_test()
    print("[audit] all checks passed")


if __name__ == "__main__":
    main()

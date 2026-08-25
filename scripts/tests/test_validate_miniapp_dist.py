from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = REPOSITORY_ROOT / "scripts" / "validate-miniapp-dist.sh"
PUBLIC_ASSET_PREFIX = "/app/react/assets/"


class MiniAppDistValidatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.dist = Path(self.temporary_directory.name) / "dist"
        self.assets = self.dist / "assets"
        self.assets.mkdir(parents=True)
        self.write_valid_fixture()

    def write_document(self, tags: list[str]) -> None:
        self.dist.joinpath("index.html").write_text(
            "<!doctype html>\n<html><head>\n" + "\n".join(tags) + "\n</head></html>\n",
            encoding="utf-8",
        )

    def write_index(self, javascript_reference: str | None, stylesheet_reference: str | None) -> None:
        tags: list[str] = []
        if javascript_reference is not None:
            tags.append(f'<script type="module" src="{javascript_reference}"></script>')
        if stylesheet_reference is not None:
            tags.append(f'<link rel="stylesheet" href="{stylesheet_reference}">')
        self.write_document(tags)

    def write_valid_fixture(self) -> None:
        self.write_index(
            f"{PUBLIC_ASSET_PREFIX}index-main.js",
            f"{PUBLIC_ASSET_PREFIX}index-main.css",
        )
        self.assets.joinpath("index-main.js").write_text("export {};\n", encoding="utf-8")
        self.assets.joinpath("index-main.css").write_text("body {}\n", encoding="utf-8")

    def run_validator(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["bash", str(VALIDATOR), str(self.dist)],
            cwd=REPOSITORY_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )

    def assert_rejected(self, expected_error: str, *redacted_values: str) -> None:
        result = self.run_validator()
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn(expected_error, result.stderr)
        self.assertNotIn("miniapp-dist: OK", result.stdout)
        self.assertEqual(1, len(result.stderr.rstrip("\n").splitlines()))
        self.assertLessEqual(len(result.stderr), 256)
        for redacted_value in redacted_values:
            self.assertNotIn(redacted_value, result.stderr)
            self.assertNotIn(redacted_value, result.stdout)

    def test_accepts_referenced_nonempty_javascript_and_stylesheet(self) -> None:
        result = self.run_validator()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("miniapp-dist: OK\n", result.stdout)
        self.assertEqual("", result.stderr)

    def test_rejects_missing_referenced_javascript(self) -> None:
        self.assets.joinpath("index-main.js").unlink()

        self.assert_rejected("referenced asset must be a nonempty regular file")

    def test_rejects_empty_referenced_javascript(self) -> None:
        self.assets.joinpath("index-main.js").write_text("", encoding="utf-8")

        self.assert_rejected("referenced asset must be a nonempty regular file")

    def test_rejects_missing_referenced_stylesheet(self) -> None:
        self.assets.joinpath("index-main.css").unlink()

        self.assert_rejected("referenced asset must be a nonempty regular file")

    def test_rejects_empty_referenced_stylesheet(self) -> None:
        self.assets.joinpath("index-main.css").write_text("", encoding="utf-8")

        self.assert_rejected("referenced asset must be a nonempty regular file")

    def test_rejects_unreferenced_stale_javascript(self) -> None:
        self.assets.joinpath("index-stale.js").write_text("export {};\n", encoding="utf-8")

        self.assert_rejected("unreferenced stale hashed asset")

    def test_rejects_unreferenced_stale_stylesheet(self) -> None:
        self.assets.joinpath("index-stale.css").write_text("body {}\n", encoding="utf-8")

        self.assert_rejected("unreferenced stale hashed asset")

    def test_rejects_traversal_reference(self) -> None:
        self.write_index(
            f"{PUBLIC_ASSET_PREFIX}../index-main.js",
            f"{PUBLIC_ASSET_PREFIX}index-main.css",
        )

        self.assert_rejected("script src must reference an exact local flat JavaScript asset")

    def test_rejects_malformed_unquoted_reference(self) -> None:
        self.dist.joinpath("index.html").write_text(
            "<!doctype html>\n"
            f"<script src={PUBLIC_ASSET_PREFIX}index-main.js></script>\n"
            f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">\n',
            encoding="utf-8",
        )

        self.assert_rejected("malformed executable/style resource attribute")

    def test_rejects_index_without_javascript_reference(self) -> None:
        self.write_index(None, f"{PUBLIC_ASSET_PREFIX}index-main.css")
        self.assets.joinpath("index-main.js").unlink()

        self.assert_rejected("index.html must reference at least one JavaScript asset")

    def test_rejects_index_without_stylesheet_reference(self) -> None:
        self.write_index(f"{PUBLIC_ASSET_PREFIX}index-main.js", None)
        self.assets.joinpath("index-main.css").unlink()

        self.assert_rejected("index.html must reference at least one stylesheet asset")

    def test_rejects_external_extensionless_script_without_echoing_url(self) -> None:
        external_url = "https://attacker.invalid/payload"
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
                f'<script src="{external_url}"></script>',
            ],
        )

        self.assert_rejected(
            "script src must reference an exact local flat JavaScript asset",
            external_url,
            "attacker.invalid",
        )

    def test_rejects_external_extensionless_stylesheet_without_echoing_url(self) -> None:
        external_url = "https://attacker.invalid/payload"
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
                f'<link href="{external_url}" rel="stylesheet">',
            ],
        )

        self.assert_rejected(
            "stylesheet href must reference an exact local flat CSS asset",
            external_url,
            "attacker.invalid",
        )

    def test_rejects_protocol_relative_script_without_echoing_host(self) -> None:
        external_url = "//attacker.invalid/payload.js"
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
                f'<script src="{external_url}"></script>',
            ],
        )

        self.assert_rejected(
            "script src must reference an exact local flat JavaScript asset",
            external_url,
            "attacker.invalid",
        )

    def test_rejects_data_script_without_echoing_value(self) -> None:
        external_value = "data:text/javascript,secretPayload"
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
                f'<script src="{external_value}"></script>',
            ],
        )

        self.assert_rejected(
            "script src must reference an exact local flat JavaScript asset",
            external_value,
            "secretPayload",
        )

    def test_rejects_blob_script_without_echoing_value(self) -> None:
        external_value = "blob:https://attacker.invalid/private-id"
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
                f'<script src="{external_value}"></script>',
            ],
        )

        self.assert_rejected(
            "script src must reference an exact local flat JavaScript asset",
            external_value,
            "attacker.invalid",
        )

    def test_rejects_nested_unreferenced_stale_javascript(self) -> None:
        nested_assets = self.assets / "nested"
        nested_assets.mkdir()
        nested_assets.joinpath("index-stale.js").write_text("export {};\n", encoding="utf-8")

        self.assert_rejected("hashed index assets must be direct children")

    def test_rejects_nested_unreferenced_stale_stylesheet(self) -> None:
        nested_assets = self.assets / "nested"
        nested_assets.mkdir()
        nested_assets.joinpath("index-stale.css").write_text("body {}\n", encoding="utf-8")

        self.assert_rejected("hashed index assets must be direct children")

    def test_rejects_nested_referenced_javascript(self) -> None:
        nested_assets = self.assets / "nested"
        nested_assets.mkdir()
        nested_assets.joinpath("index-main.js").write_text("export {};\n", encoding="utf-8")
        self.assets.joinpath("index-main.js").unlink()
        self.write_index(
            f"{PUBLIC_ASSET_PREFIX}nested/index-main.js",
            f"{PUBLIC_ASSET_PREFIX}index-main.css",
        )

        self.assert_rejected("script src must reference an exact local flat JavaScript asset")

    def test_rejects_nested_referenced_stylesheet(self) -> None:
        nested_assets = self.assets / "nested"
        nested_assets.mkdir()
        nested_assets.joinpath("index-main.css").write_text("body {}\n", encoding="utf-8")
        self.assets.joinpath("index-main.css").unlink()
        self.write_index(
            f"{PUBLIC_ASSET_PREFIX}index-main.js",
            f"{PUBLIC_ASSET_PREFIX}nested/index-main.css",
        )

        self.assert_rejected("stylesheet href must reference an exact local flat CSS asset")

    def test_rejects_unexpected_script_extension_even_with_valid_assets(self) -> None:
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
                f'<script src="{PUBLIC_ASSET_PREFIX}payload.mjs"></script>',
            ],
        )

        self.assert_rejected("script src must reference an exact local flat JavaScript asset")

    def test_rejects_unexpected_local_script_path_even_with_valid_assets(self) -> None:
        unexpected_path = "/app/react/payload.js"
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
                f'<script src="{unexpected_path}"></script>',
            ],
        )

        self.assert_rejected(
            "script src must reference an exact local flat JavaScript asset",
            unexpected_path,
        )

    def test_accepts_base_href_with_valid_local_assets(self) -> None:
        self.write_document(
            [
                '<base href="/app/react/">',
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
            ],
        )

        result = self.run_validator()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("miniapp-dist: OK\n", result.stdout)

    def test_recognizes_stylesheet_rel_case_and_attribute_order(self) -> None:
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                (
                    f'<LiNk HREF="{PUBLIC_ASSET_PREFIX}index-main.css" '
                    'crossorigin REL="StYlEsHeEt">'
                ),
            ],
        )

        result = self.run_validator()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("miniapp-dist: OK\n", result.stdout)

    def test_rejects_modulepreload_explicitly(self) -> None:
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
                f'<link href="{PUBLIC_ASSET_PREFIX}index-main.js" rel="modulepreload">',
            ],
        )

        self.assert_rejected("modulepreload resources are unsupported")

    def test_rejects_duplicate_referenced_asset_name(self) -> None:
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<script type="module" src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
            ],
        )

        self.assert_rejected("referenced asset names must be unique")

    def test_rejects_script_tag_without_src_even_with_valid_assets(self) -> None:
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
                '<script></script>',
            ],
        )

        self.assert_rejected("every script tag must contain exactly one quoted src")

    def test_rejects_stylesheet_without_href_even_with_valid_assets(self) -> None:
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
                '<link media="all" rel="stylesheet">',
            ],
        )

        self.assert_rejected("every stylesheet link must contain exactly one quoted href")

    def test_accepts_single_quoted_local_script_and_stylesheet(self) -> None:
        self.write_document(
            [
                f"<script src='{PUBLIC_ASSET_PREFIX}index-main.js'></script>",
                f"<link href='{PUBLIC_ASSET_PREFIX}index-main.css' rel='stylesheet'>",
            ],
        )

        result = self.run_validator()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("miniapp-dist: OK\n", result.stdout)

    def test_rejects_entity_encoded_stylesheet_rel_without_echoing_url(self) -> None:
        external_url = "https://attacker.invalid/encoded-stylesheet"
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
                f'<link rel="style&#115;heet" href="{external_url}">',
            ],
        )

        self.assert_rejected(
            "malformed executable/style resource attribute",
            external_url,
            "attacker.invalid",
        )

    def test_rejects_entity_encoded_modulepreload_rel_without_echoing_url(self) -> None:
        external_url = "https://attacker.invalid/encoded-module.js"
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
                f'<link rel="modulepre&#108;oad" href="{external_url}">',
            ],
        )

        self.assert_rejected(
            "malformed executable/style resource attribute",
            external_url,
            "attacker.invalid",
        )

    def test_rejects_external_preload_script_without_echoing_url(self) -> None:
        external_url = "https://attacker.invalid/preload-script"
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
                f'<link href="{external_url}" as="ScRiPt" rel="PRELOAD">',
            ],
        )

        self.assert_rejected(
            "preload and prefetch script/style resources are unsupported",
            external_url,
            "attacker.invalid",
        )

    def test_rejects_external_preload_style_without_echoing_url(self) -> None:
        external_url = "https://attacker.invalid/preload-style"
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
                f'<link rel="preload" as="style" href="{external_url}">',
            ],
        )

        self.assert_rejected(
            "preload and prefetch script/style resources are unsupported",
            external_url,
            "attacker.invalid",
        )

    def test_rejects_untyped_external_prefetch_without_echoing_url(self) -> None:
        external_url = "https://attacker.invalid/prefetch-payload"
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
                f'<link href="{external_url}" rel="prefetch">',
            ],
        )

        self.assert_rejected(
            "preload and prefetch script/style resources are unsupported",
            external_url,
            "attacker.invalid",
        )

    def test_rejects_ambiguous_html_comment_without_echoing_url(self) -> None:
        external_url = "https://attacker.invalid/payload"
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
                f'<!--><script src="{external_url}"></script><!-->',
            ],
        )

        self.assert_rejected(
            "malformed executable/style resource attribute",
            external_url,
            "attacker.invalid",
        )

    def test_rejects_missing_long_safe_basename_with_bounded_diagnostic(self) -> None:
        long_reference = f"{PUBLIC_ASSET_PREFIX}{'s' * 1024}.js"
        self.write_document(
            [
                f'<script src="{PUBLIC_ASSET_PREFIX}index-main.js"></script>',
                f'<link rel="stylesheet" href="{PUBLIC_ASSET_PREFIX}index-main.css">',
                f'<script src="{long_reference}"></script>',
            ],
        )

        self.assert_rejected("referenced asset must be a nonempty regular file", long_reference)

    def test_rejects_referenced_asset_symlink(self) -> None:
        self.assets.joinpath("index-main.js").unlink()
        symlink_target = self.dist / "real-index-main.js"
        symlink_target.write_text("export {};\n", encoding="utf-8")
        self.assets.joinpath("index-main.js").symlink_to(symlink_target)

        self.assert_rejected("referenced asset must be a nonempty regular file")


if __name__ == "__main__":
    unittest.main()

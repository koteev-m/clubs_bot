from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
from dataclasses import dataclass, replace
from email.message import Message
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from typing import Any, Callable
import unittest
from unittest import mock
from urllib.parse import urlencode
from urllib.request import Request


MODULE_PATH = Path(__file__).resolve().parents[1] / "verify-oci-provenance.py"
MODULE_NAME = "clubs_bot_verify_oci_provenance"
SPEC = importlib.util.spec_from_file_location(MODULE_NAME, MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("cannot load production OCI verifier")
verifier = importlib.util.module_from_spec(SPEC)
sys.modules[MODULE_NAME] = verifier
SPEC.loader.exec_module(verifier)


REPOSITORY = "koteev-m/clubs_bot"
REF = "refs/heads/main"
SHA = "0123456789abcdef0123456789abcdef01234567"
RUN_ID = "123456789"
RUN_ATTEMPT = "2"
WORKFLOW_NAME = "Docker Publish (GHCR)"
EVENT_NAME = "push"
WORKFLOW_REF = (
    f"{REPOSITORY}/.github/workflows/docker-publish.yml@{REF}"
)


def encode(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def sha256_digest(raw: bytes) -> str:
    return f"sha256:{hashlib.sha256(raw).hexdigest()}"


def descriptor(
    raw: bytes,
    media_type: str,
    *,
    platform: dict[str, str] | None = None,
    annotations: dict[str, str] | None = None,
) -> dict[str, Any]:
    value: dict[str, Any] = {
        "mediaType": media_type,
        "digest": sha256_digest(raw),
        "size": len(raw),
    }
    if platform is not None:
        value["platform"] = platform
    if annotations is not None:
        value["annotations"] = annotations
    return value


def response_headers(
    digest: str | None = None,
    content_type: str | None = None,
) -> Message:
    headers = Message()
    if digest is not None:
        headers.add_header("Docker-Content-Digest", digest)
    if content_type is not None:
        headers.add_header("Content-Type", content_type)
    return headers


@dataclass
class FakeRoute:
    body: bytes
    headers: Message


class FakeRegistryTransport:
    """Injected registry boundary; it never stores credential values."""

    def __init__(self, image_path: str, routes: dict[str, FakeRoute]) -> None:
        self.image_path = image_path
        self.routes = routes
        self.calls: list[dict[str, Any]] = []

    def fetch(
        self,
        image_path: str,
        url: str,
        request_type: str,
        expected_digest: str | None = None,
        authorization: str | None = None,
        accept: str | None = None,
    ) -> tuple[bytes, Message]:
        if image_path != self.image_path:
            raise verifier.NetworkError("fake registry scope mismatch")
        self.calls.append(
            {
                "url": url,
                "request_type": request_type,
                "expected_digest": expected_digest,
                "authorization_scheme": (
                    authorization.split(" ", 1)[0] if authorization else None
                ),
                "accept": accept,
            }
        )
        route = self.routes.get(url)
        if route is None:
            raise verifier.NetworkError("fake registry route is missing")
        return route.body, route.headers


@dataclass
class FixtureGraph:
    context: Any
    transport: FakeRegistryTransport
    index: dict[str, Any]
    index_url: str
    platform_urls: dict[str, str]
    attestation_urls: dict[str, str]
    statements: dict[str, dict[str, Any]]

    def platform_descriptor(self, platform_name: str) -> dict[str, Any]:
        os_name, architecture = platform_name.split("/", 1)
        matches = [
            item
            for item in self.index["manifests"]
            if item.get("platform")
            == {"os": os_name, "architecture": architecture}
        ]
        if len(matches) != 1:
            raise AssertionError("fixture platform descriptor is ambiguous")
        return matches[0]

    def attestation_descriptors(self) -> list[dict[str, Any]]:
        return [
            item
            for item in self.index["manifests"]
            if item.get("annotations", {}).get("vnd.docker.reference.type")
            == "attestation-manifest"
        ]

    def rebuild_index(self) -> None:
        old_url = self.index_url
        old_route = self.transport.routes.pop(old_url)
        raw = encode(self.index)
        digest = sha256_digest(raw)
        self.context = replace(
            self.context,
            image_ref=f"ghcr.io/{self.context.image_path}@{digest}",
        )
        self.index_url = f"{self.context.repository_url}/manifests/{digest}"
        content_type = old_route.headers.get("Content-Type") or verifier.OCI_INDEX
        self.transport.routes[self.index_url] = FakeRoute(
            raw,
            response_headers(digest, content_type),
        )


StatementMutator = Callable[[str, dict[str, Any]], None]
PlatformMutator = Callable[[str, dict[str, Any]], None]
AttestationMutator = Callable[[str, dict[str, Any]], None]
IndexMutator = Callable[[dict[str, Any]], None]


def build_graph(
    *,
    statement_mutator: StatementMutator | None = None,
    platform_mutator: PlatformMutator | None = None,
    attestation_mutator: AttestationMutator | None = None,
    index_mutator: IndexMutator | None = None,
    docker_platform: str | None = None,
    attestation_mode: str = "legacy",
    attestation_target_overrides: dict[str, str] | None = None,
) -> FixtureGraph:
    if attestation_mode not in {"legacy", "modern-inline", "modern-fetched"}:
        raise ValueError("unsupported fixture attestation mode")
    context = verifier.VerificationContext(
        image_ref="pending",
        repository=REPOSITORY,
        expected_ref=REF,
        expected_sha=SHA,
        run_id=RUN_ID,
        run_attempt=RUN_ATTEMPT,
        workflow_ref=WORKFLOW_REF,
        workflow_name=WORKFLOW_NAME,
        event_name=EVENT_NAME,
    )
    routes: dict[str, FakeRoute] = {}
    token_query = urlencode(
        {
            "service": "ghcr.io",
            "scope": f"repository:{context.image_path}:pull",
        }
    )
    routes[f"https://ghcr.io/token?{token_query}"] = FakeRoute(
        encode({"token": "fixture-bearer-token"}),
        Message(),
    )

    platform_descriptors: dict[str, dict[str, Any]] = {}
    platform_urls: dict[str, str] = {}
    platform_digests: dict[str, str] = {}
    for platform_name in sorted(verifier.EXPECTED_PLATFORMS):
        use_docker = platform_name == docker_platform
        manifest_type = (
            verifier.DOCKER_MANIFEST if use_docker else verifier.OCI_MANIFEST
        )
        config_type = (
            verifier.DOCKER_CONFIG if use_docker else verifier.OCI_CONFIG
        )
        layer_type = (
            "application/vnd.docker.image.rootfs.diff.tar.gzip"
            if use_docker
            else "application/vnd.oci.image.layer.v1.tar+gzip"
        )
        config_raw = f"platform-config:{platform_name}".encode("ascii")
        layer_raw = f"platform-layer:{platform_name}".encode("ascii")
        platform_manifest = {
            "schemaVersion": 2,
            "mediaType": manifest_type,
            "config": descriptor(config_raw, config_type),
            "layers": [descriptor(layer_raw, layer_type)],
        }
        if platform_mutator is not None:
            platform_mutator(platform_name, platform_manifest)
        platform_raw = encode(platform_manifest)
        platform_digest = sha256_digest(platform_raw)
        platform_digests[platform_name] = platform_digest
        os_name, architecture = platform_name.split("/", 1)
        platform_descriptor = descriptor(
            platform_raw,
            manifest_type,
            platform={"os": os_name, "architecture": architecture},
        )
        platform_descriptors[platform_name] = platform_descriptor
        platform_url = (
            f"{context.repository_url}/manifests/{platform_digest}"
        )
        platform_urls[platform_name] = platform_url
        routes[platform_url] = FakeRoute(
            platform_raw,
            response_headers(platform_digest, manifest_type),
        )

    statements: dict[str, dict[str, Any]] = {}
    attestation_descriptors: list[dict[str, Any]] = []
    attestation_urls: dict[str, str] = {}
    for platform_name in sorted(verifier.EXPECTED_PLATFORMS):
        attested_platform_name = (attestation_target_overrides or {}).get(
            platform_name,
            platform_name,
        )
        platform_digest = platform_digests[attested_platform_name]
        subject_name = (
            f"pkg:docker/ghcr.io/{context.image_path}@sha-{SHA[:7]}"
            f"?platform={attested_platform_name.replace('/', '%2F')}"
        )
        statement = {
            "_type": verifier.IN_TOTO_STATEMENT,
            "predicateType": verifier.SLSA_PROVENANCE,
            "subject": [
                {
                    "name": subject_name,
                    "digest": {"sha256": platform_digest.removeprefix("sha256:")},
                }
            ],
            "predicate": {
                "buildDefinition": {
                    "buildType": verifier.BUILDKIT_BUILD_TYPE,
                    "internalParameters": {
                        "github_event_name": EVENT_NAME,
                        "github_job": "build-and-push",
                        "github_ref": REF,
                        "github_repository": REPOSITORY,
                        "github_run_attempt": RUN_ATTEMPT,
                        "github_run_id": RUN_ID,
                        "github_workflow": WORKFLOW_NAME,
                        "github_workflow_ref": WORKFLOW_REF,
                        "github_workflow_sha": SHA,
                    },
                    "externalParameters": {
                        "request": {
                            "root": {
                                "request": {
                                    "args": {
                                        "vcs:revision": SHA,
                                        "vcs:source": f"https://github.com/{REPOSITORY}",
                                    }
                                }
                            }
                        }
                    },
                },
                "runDetails": {
                    "builder": {
                        "id": (
                            f"https://github.com/{REPOSITORY}/actions/runs/"
                            f"{RUN_ID}/attempts/{RUN_ATTEMPT}"
                        )
                    }
                },
            },
        }
        if statement_mutator is not None:
            statement_mutator(platform_name, statement)
        statements[platform_name] = statement
        statement_raw = encode(statement)
        layer = descriptor(
            statement_raw,
            verifier.IN_TOTO,
            annotations={
                "in-toto.io/predicate-type": verifier.SLSA_PROVENANCE,
            },
        )
        if attestation_mode == "legacy":
            config_document = {
                "architecture": "unknown",
                "os": "unknown",
                "config": {},
                "rootfs": {
                    "type": "layers",
                    "diff_ids": [layer["digest"]],
                },
            }
            config_raw = encode(config_document)
            config = descriptor(config_raw, verifier.OCI_CONFIG)
        else:
            config_raw = b"{}"
            config = descriptor(config_raw, verifier.OCI_EMPTY_CONFIG)
            if attestation_mode == "modern-inline":
                config["data"] = verifier.EMPTY_JSON_DATA
        attestation_manifest = {
            "schemaVersion": 2,
            "mediaType": verifier.OCI_MANIFEST,
            "config": config,
            "layers": [layer],
        }
        if attestation_mode != "legacy":
            platform_descriptor = platform_descriptors[attested_platform_name]
            attestation_manifest["artifactType"] = (
                verifier.DOCKER_ATTESTATION_ARTIFACT
            )
            attestation_manifest["subject"] = {
                key: platform_descriptor[key]
                for key in ("mediaType", "digest", "size")
            }
        if attestation_mutator is not None:
            attestation_mutator(platform_name, attestation_manifest)
        attestation_raw = encode(attestation_manifest)
        attestation_digest = sha256_digest(attestation_raw)
        attestation_descriptor = descriptor(
            attestation_raw,
            verifier.OCI_MANIFEST,
            platform={"os": "unknown", "architecture": "unknown"},
            annotations={
                "vnd.docker.reference.type": "attestation-manifest",
                "vnd.docker.reference.digest": platform_digest,
            },
        )
        attestation_descriptors.append(attestation_descriptor)
        attestation_url = (
            f"{context.repository_url}/manifests/{attestation_digest}"
        )
        attestation_urls[platform_name] = attestation_url
        routes[attestation_url] = FakeRoute(
            attestation_raw,
            response_headers(attestation_digest, verifier.OCI_MANIFEST),
        )
        config_digest = config.get("digest")
        if isinstance(config_digest, str):
            routes[
                f"{context.repository_url}/blobs/{config_digest}"
            ] = FakeRoute(config_raw, Message())
        routes[f"{context.repository_url}/blobs/{layer['digest']}"] = FakeRoute(
            statement_raw,
            Message(),
        )

    index = {
        "schemaVersion": 2,
        "mediaType": verifier.OCI_INDEX,
        "manifests": [
            platform_descriptors["linux/amd64"],
            platform_descriptors["linux/arm64"],
            *attestation_descriptors,
        ],
    }
    if index_mutator is not None:
        index_mutator(index)
    index_raw = encode(index)
    index_digest = sha256_digest(index_raw)
    context = replace(
        context,
        image_ref=f"ghcr.io/{context.image_path}@{index_digest}",
    )
    index_url = f"{context.repository_url}/manifests/{index_digest}"
    routes[index_url] = FakeRoute(
        index_raw,
        response_headers(index_digest, verifier.OCI_INDEX),
    )
    transport = FakeRegistryTransport(context.image_path, routes)
    return FixtureGraph(
        context=context,
        transport=transport,
        index=index,
        index_url=index_url,
        platform_urls=platform_urls,
        attestation_urls=attestation_urls,
        statements=statements,
    )


def verifier_args(context: Any) -> list[str]:
    return [
        "--image-ref",
        context.image_ref,
        "--repository",
        context.repository,
        "--ref",
        context.expected_ref,
        "--sha",
        context.expected_sha,
        "--run-id",
        context.run_id,
        "--run-attempt",
        context.run_attempt,
        "--workflow-ref",
        context.workflow_ref,
        "--workflow-name",
        context.workflow_name,
        "--event-name",
        context.event_name,
    ]


class OciVerifierBehaviorTest(unittest.TestCase):
    def assert_policy_failure(
        self,
        graph: FixtureGraph,
        diagnostic: str,
    ) -> None:
        with self.assertRaises(verifier.PolicyError) as caught:
            verifier.verify_oci_provenance(graph.context, graph.transport)
        self.assertIn(diagnostic, str(caught.exception))

    def assert_modern_policy_failure(
        self,
        mutator: Callable[[dict[str, Any]], None],
        diagnostic: str,
    ) -> None:
        def mutate_arm64(
            platform_name: str,
            manifest: dict[str, Any],
        ) -> None:
            if platform_name == "linux/arm64":
                mutator(manifest)

        graph = build_graph(
            attestation_mode="modern-inline",
            attestation_mutator=mutate_arm64,
        )
        self.assert_policy_failure(graph, diagnostic)

    def test_valid_amd64_arm64_graph(self) -> None:
        graph = build_graph()
        statements = verifier.verify_oci_provenance(
            graph.context,
            graph.transport,
            github_token="workflow-token-not-logged",
            actor="fixture-actor",
        )
        self.assertEqual(
            [platform for platform, _ in statements],
            ["linux/amd64", "linux/arm64"],
        )
        self.assertEqual(len(statements), 2)
        self.assertEqual(
            graph.transport.calls[0]["authorization_scheme"],
            "Basic",
        )
        self.assertTrue(
            all(
                call["authorization_scheme"] == "Bearer"
                for call in graph.transport.calls[1:]
            )
        )
        self.assertNotIn(
            "workflow-token-not-logged",
            json.dumps(graph.transport.calls),
        )

    def test_modern_oci_artifact_inline_empty_config_is_accepted(self) -> None:
        graph = build_graph(attestation_mode="modern-inline")
        statements = verifier.verify_oci_provenance(
            graph.context,
            graph.transport,
        )
        self.assertEqual(
            [platform for platform, _ in statements],
            ["linux/amd64", "linux/arm64"],
        )
        config_calls = [
            call
            for call in graph.transport.calls
            if call["request_type"] == verifier.CONFIG_BLOB_REQUEST
        ]
        self.assertEqual(config_calls, [])

    def test_modern_oci_artifact_fetched_empty_config_is_accepted(self) -> None:
        graph = build_graph(attestation_mode="modern-fetched")
        statements = verifier.verify_oci_provenance(
            graph.context,
            graph.transport,
        )
        self.assertEqual(len(statements), 2)
        config_calls = [
            call
            for call in graph.transport.calls
            if call["request_type"] == verifier.CONFIG_BLOB_REQUEST
        ]
        self.assertEqual(len(config_calls), 2)
        self.assertEqual(
            {call["expected_digest"] for call in config_calls},
            {verifier.EMPTY_JSON_DIGEST},
        )

    def test_modern_index_reference_annotation_is_optional(self) -> None:
        def remove_reference_annotations(index: dict[str, Any]) -> None:
            for item in index["manifests"]:
                annotations = item.get("annotations", {})
                if (
                    annotations.get("vnd.docker.reference.type")
                    == "attestation-manifest"
                ):
                    annotations.pop("vnd.docker.reference.digest")

        graph = build_graph(
            attestation_mode="modern-inline",
            index_mutator=remove_reference_annotations,
        )
        statements = verifier.verify_oci_provenance(
            graph.context,
            graph.transport,
        )
        self.assertEqual(
            [platform for platform, _ in statements],
            ["linux/amd64", "linux/arm64"],
        )

    def test_legacy_buildkit_compatibility_is_explicitly_accepted(self) -> None:
        graph = build_graph(attestation_mode="legacy")
        statements = verifier.verify_oci_provenance(
            graph.context,
            graph.transport,
        )
        self.assertEqual(len(statements), 2)
        self.assertEqual(
            sum(
                call["request_type"] == verifier.CONFIG_BLOB_REQUEST
                for call in graph.transport.calls
            ),
            2,
        )

    def test_valid_docker_v2_platform_manifest_branch(self) -> None:
        graph = build_graph(docker_platform="linux/arm64")
        statements = verifier.verify_oci_provenance(
            graph.context,
            graph.transport,
        )
        self.assertEqual(len(statements), 2)

    def test_missing_and_wrong_platform_body(self) -> None:
        graph = build_graph()
        del graph.transport.routes[graph.platform_urls["linux/arm64"]]
        with self.assertRaises(verifier.NetworkError):
            verifier.verify_oci_provenance(graph.context, graph.transport)

        graph = build_graph()
        route = graph.transport.routes[graph.platform_urls["linux/arm64"]]
        route.body += b"corrupt"
        self.assert_policy_failure(graph, "size does not match its descriptor")

    def test_platform_size_and_sha_are_descriptor_bound(self) -> None:
        graph = build_graph()
        graph.platform_descriptor("linux/arm64")["size"] += 1
        graph.rebuild_index()
        self.assert_policy_failure(graph, "size does not match its descriptor")

        graph = build_graph()
        original_url = graph.platform_urls["linux/arm64"]
        original_route = graph.transport.routes[original_url]
        bad_digest = f"sha256:{'f' * 64}"
        graph.platform_descriptor("linux/arm64")["digest"] = bad_digest
        graph.rebuild_index()
        bad_url = f"{graph.context.repository_url}/manifests/{bad_digest}"
        graph.transport.routes[bad_url] = FakeRoute(
            original_route.body,
            response_headers(bad_digest, verifier.OCI_MANIFEST),
        )
        self.assert_policy_failure(graph, "content digest does not match")

    def test_manifest_digest_headers_are_single_and_canonical(self) -> None:
        for target in ("index", "platform", "attestation"):
            with self.subTest(target=target):
                graph = build_graph()
                url = {
                    "index": graph.index_url,
                    "platform": graph.platform_urls["linux/amd64"],
                    "attestation": graph.attestation_urls["linux/amd64"],
                }[target]
                route = graph.transport.routes[url]
                route.headers.add_header(
                    "docker-content-digest",
                    route.headers.get("Docker-Content-Digest"),
                )
                self.assert_policy_failure(
                    graph,
                    "must have exactly one Docker-Content-Digest",
                )

        graph = build_graph()
        route = graph.transport.routes[graph.platform_urls["linux/amd64"]]
        route.headers = response_headers(None, verifier.OCI_MANIFEST)
        self.assert_policy_failure(graph, "missing Docker-Content-Digest")

        graph = build_graph()
        route = graph.transport.routes[graph.platform_urls["linux/amd64"]]
        route.headers = response_headers(
            f"sha256:{'e' * 64}",
            verifier.OCI_MANIFEST,
        )
        self.assert_policy_failure(graph, "did not confirm the requested")

    def test_platform_header_media_and_schema_validation(self) -> None:
        graph = build_graph()
        route = graph.transport.routes[graph.platform_urls["linux/arm64"]]
        route.headers.replace_header("Content-Type", "application/json")
        self.assert_policy_failure(graph, "Content-Type does not match")

        graph = build_graph(
            platform_mutator=lambda platform, manifest: (
                manifest.__setitem__("mediaType", verifier.DOCKER_MANIFEST)
                if platform == "linux/arm64"
                else None
            )
        )
        self.assert_policy_failure(graph, "media type does not match")

        graph = build_graph(
            platform_mutator=lambda platform, manifest: (
                manifest.__setitem__("schemaVersion", 1)
                if platform == "linux/arm64"
                else None
            )
        )
        self.assert_policy_failure(graph, "schemaVersion is not 2")

    def test_modern_missing_artifact_type_is_rejected(self) -> None:
        self.assert_modern_policy_failure(
            lambda manifest: manifest.pop("artifactType"),
            "artifactType",
        )

    def test_modern_wrong_artifact_type_is_rejected(self) -> None:
        self.assert_modern_policy_failure(
            lambda manifest: manifest.__setitem__(
                "artifactType",
                "application/vnd.example.attestation.v1+json",
            ),
            "artifactType",
        )

    def test_modern_missing_subject_is_rejected(self) -> None:
        self.assert_modern_policy_failure(
            lambda manifest: manifest.pop("subject"),
            "subject",
        )

    def test_modern_schema_version_must_be_integer_two(self) -> None:
        self.assert_modern_policy_failure(
            lambda manifest: manifest.__setitem__("schemaVersion", 2.0),
            "not an OCI manifest",
        )

    def test_modern_subject_must_be_canonical_buildkit_descriptor(self) -> None:
        self.assert_modern_policy_failure(
            lambda manifest: manifest["subject"].__setitem__(
                "unknownField",
                {},
            ),
            "canonical BuildKit descriptor",
        )

    def test_modern_subject_digest_mismatch_is_rejected(self) -> None:
        self.assert_modern_policy_failure(
            lambda manifest: manifest["subject"].__setitem__(
                "digest",
                f"sha256:{'f' * 64}",
            ),
            "subject digest",
        )

    def test_modern_subject_size_mismatch_is_rejected(self) -> None:
        self.assert_modern_policy_failure(
            lambda manifest: manifest["subject"].__setitem__(
                "size",
                manifest["subject"]["size"] + 1,
            ),
            "subject size",
        )

    def test_modern_subject_media_type_mismatch_is_rejected(self) -> None:
        self.assert_modern_policy_failure(
            lambda manifest: manifest["subject"].__setitem__(
                "mediaType",
                verifier.DOCKER_MANIFEST,
            ),
            "subject media type",
        )

    def test_modern_wrong_empty_config_media_type_is_rejected(self) -> None:
        self.assert_modern_policy_failure(
            lambda manifest: manifest["config"].__setitem__(
                "mediaType",
                "application/vnd.example.empty.v1+json",
            ),
            "empty config media type",
        )

    def test_modern_wrong_empty_config_digest_is_rejected(self) -> None:
        self.assert_modern_policy_failure(
            lambda manifest: manifest["config"].__setitem__(
                "digest",
                f"sha256:{'e' * 64}",
            ),
            "empty config digest",
        )

    def test_modern_wrong_empty_config_size_is_rejected(self) -> None:
        self.assert_modern_policy_failure(
            lambda manifest: manifest["config"].__setitem__("size", 3),
            "empty config size",
        )

    def test_modern_invalid_base64_config_data_is_rejected(self) -> None:
        self.assert_modern_policy_failure(
            lambda manifest: manifest["config"].__setitem__("data", "%%%%"),
            "base64",
        )

    def test_modern_decoded_config_data_must_be_empty_json(self) -> None:
        self.assert_modern_policy_failure(
            lambda manifest: manifest["config"].__setitem__("data", "W10="),
            "empty JSON",
        )

    def test_modern_fetched_config_blob_must_be_empty_json(self) -> None:
        graph = build_graph(attestation_mode="modern-fetched")
        config_url = (
            f"{graph.context.repository_url}/blobs/"
            f"{verifier.EMPTY_JSON_DIGEST}"
        )
        graph.transport.routes[config_url].body = b"[]"
        self.assert_policy_failure(graph, "empty JSON")

    def test_modern_artifact_with_legacy_config_is_rejected(self) -> None:
        def use_legacy_config(manifest: dict[str, Any]) -> None:
            layer_digest = manifest["layers"][0]["digest"]
            legacy_raw = encode(
                {
                    "architecture": "unknown",
                    "os": "unknown",
                    "config": {},
                    "rootfs": {
                        "type": "layers",
                        "diff_ids": [layer_digest],
                    },
                }
            )
            manifest["config"] = descriptor(legacy_raw, verifier.OCI_CONFIG)

        self.assert_modern_policy_failure(
            use_legacy_config,
            "empty config media type",
        )

    def test_empty_config_without_modern_contract_is_rejected(self) -> None:
        def use_empty_config(
            platform_name: str,
            manifest: dict[str, Any],
        ) -> None:
            if platform_name == "linux/arm64":
                manifest["config"] = {
                    "mediaType": verifier.OCI_EMPTY_CONFIG,
                    "digest": verifier.EMPTY_JSON_DIGEST,
                    "size": verifier.EMPTY_JSON_SIZE,
                    "data": verifier.EMPTY_JSON_DATA,
                }

        graph = build_graph(
            attestation_mode="legacy",
            attestation_mutator=use_empty_config,
        )
        self.assert_policy_failure(graph, "legacy attestation config")

    def test_modern_and_legacy_platform_attestations_cannot_be_mixed(self) -> None:
        def remove_modern_contract(
            platform_name: str,
            manifest: dict[str, Any],
        ) -> None:
            if platform_name == "linux/arm64":
                manifest.pop("artifactType")
                manifest.pop("subject")

        graph = build_graph(
            attestation_mode="modern-inline",
            attestation_mutator=remove_modern_contract,
        )
        self.assert_policy_failure(graph, "mix modern and legacy")

    def test_partial_and_duplicate_provenance_graphs_are_rejected(self) -> None:
        def remove_last_attestation(index: dict[str, Any]) -> None:
            index["manifests"].pop()

        graph = build_graph(
            attestation_mode="modern-inline",
            index_mutator=remove_last_attestation,
        )
        self.assert_policy_failure(graph, "exactly one provenance manifest")

        graph = build_graph(
            attestation_mode="modern-inline",
            attestation_target_overrides={"linux/arm64": "linux/amd64"},
        )
        self.assert_policy_failure(graph, "cover each verified platform exactly once")

    def test_modern_index_reference_must_match_subject_digest(self) -> None:
        def mismatch_reference(index: dict[str, Any]) -> None:
            attestations = [
                item
                for item in index["manifests"]
                if item.get("annotations", {}).get(
                    "vnd.docker.reference.type"
                )
                == "attestation-manifest"
            ]
            attestations[1]["annotations"][
                "vnd.docker.reference.digest"
            ] = attestations[0]["annotations"][
                "vnd.docker.reference.digest"
            ]

        graph = build_graph(
            attestation_mode="modern-inline",
            index_mutator=mismatch_reference,
        )
        self.assert_policy_failure(graph, "reference annotation")

    def test_wrong_slsa_statement_predicate_and_subject_are_rejected(self) -> None:
        def wrong_type(platform: str, statement: dict[str, Any]) -> None:
            if platform == "linux/arm64":
                statement["_type"] = "https://in-toto.io/Statement/v0.1"

        graph = build_graph(statement_mutator=wrong_type)
        self.assert_policy_failure(graph, "_type is not in-toto Statement v1")

        def wrong_predicate(platform: str, statement: dict[str, Any]) -> None:
            if platform == "linux/arm64":
                statement["predicateType"] = "https://slsa.dev/provenance/v0.2"

        graph = build_graph(statement_mutator=wrong_predicate)
        self.assert_policy_failure(graph, "predicateType is not SLSA provenance v1")

        def wrong_subject(platform: str, statement: dict[str, Any]) -> None:
            if platform == "linux/arm64":
                statement["subject"][0]["digest"]["sha256"] = "0" * 64

        graph = build_graph(statement_mutator=wrong_subject)
        self.assert_policy_failure(graph, "subject digest does not match")

    def test_empty_and_duplicate_statement_subjects_are_rejected(self) -> None:
        def empty_subject(platform: str, statement: dict[str, Any]) -> None:
            if platform == "linux/arm64":
                statement["subject"] = []

        graph = build_graph(statement_mutator=empty_subject)
        self.assert_policy_failure(graph, "subject is empty")

        def duplicate_subject(platform: str, statement: dict[str, Any]) -> None:
            if platform == "linux/arm64":
                statement["subject"].append(dict(statement["subject"][0]))

        graph = build_graph(statement_mutator=duplicate_subject)
        self.assert_policy_failure(graph, "subject name is duplicated")

    def test_exact_workflow_metadata_and_builder_identity_are_required(self) -> None:
        def wrong_job(platform: str, statement: dict[str, Any]) -> None:
            if platform == "linux/arm64":
                statement["predicate"]["buildDefinition"][
                    "internalParameters"
                ]["github_job"] = "other-job"

        graph = build_graph(statement_mutator=wrong_job)
        self.assert_policy_failure(graph, "metadata mismatch: github_job")

        def wrong_builder(platform: str, statement: dict[str, Any]) -> None:
            if platform == "linux/arm64":
                statement["predicate"]["runDetails"]["builder"][
                    "id"
                ] = "https://example.invalid/builder"

        graph = build_graph(statement_mutator=wrong_builder)
        self.assert_policy_failure(graph, "builder identity does not match")


@unittest.skipUnless(
    os.environ.get("RUN_LIVE_OCI_PROVENANCE_TEST") == "1",
    "set RUN_LIVE_OCI_PROVENANCE_TEST=1 for the read-only GHCR check",
)
class LiveOciVerifierTest(unittest.TestCase):
    def test_current_published_digest_has_two_platform_statements(self) -> None:
        context = verifier.VerificationContext(
            image_ref=(
                "ghcr.io/koteev-m/clubs_bot/app-bot@"
                "sha256:a77c67453e638245a82fa7bdfea520a9c8b423776aad159aa"
                "266434b11281973"
            ),
            repository="koteev-m/clubs_bot",
            expected_ref="refs/heads/main",
            expected_sha="119195e810ac9f4c901d3dc1fa8048c85f53783e",
            run_id="31731324119",
            run_attempt="1",
            workflow_ref=(
                "koteev-m/clubs_bot/.github/workflows/"
                "docker-publish.yml@refs/heads/main"
            ),
            workflow_name="Docker Publish (GHCR)",
            event_name="push",
        )
        github_token = os.environ.get("GH_TOKEN", "")
        actor = os.environ.get("REGISTRY_ACTOR", "") if github_token else ""
        if github_token and not actor:
            self.fail("REGISTRY_ACTOR is required when GH_TOKEN is set")
        statements = verifier.verify_oci_provenance(
            context,
            verifier.UrllibRegistryTransport(context.image_path),
            github_token=github_token,
            actor=actor,
        )
        self.assertEqual(
            [platform for platform, _ in statements],
            ["linux/amd64", "linux/arm64"],
        )
        self.assertEqual(len(statements), 2)
        self.assertTrue(
            all(
                statement.get("_type") == verifier.IN_TOTO_STATEMENT
                and statement.get("predicateType") == verifier.SLSA_PROVENANCE
                for _, statement in statements
            )
        )


class RedirectPolicyTest(unittest.TestCase):
    image_path = f"{REPOSITORY}/app-bot"
    digest = f"sha256:{'a' * 64}"

    def signed_blob_url(
        self,
        *,
        digest: str | None = None,
        bucket: str = "ghcrblobs01",
        **changes: str,
    ) -> str:
        query = {
            "hmac": "b" * 64,
            "se": "2026-08-12T12:00:00Z",
            "sig": "AbCdEf0123_-==",
            "ske": "2026-08-12T12:00:00Z",
            "skoid": "01234567-89ab-cdef-0123-456789abcdef",
            "sks": "b",
            "skt": "2026-08-12T12:00:00Z",
            "sktid": "abcdef01-2345-6789-abcd-ef0123456789",
            "skv": "2026-08-12",
            "sp": "r",
            "spr": "https",
            "sr": "b",
            "sv": "2026-08-12",
        }
        query.update(changes)
        return (
            "https://pkg-containers.githubusercontent.com/"
            f"{bucket}/blobs/{digest or self.digest}?{urlencode(query)}"
        )

    def source_blob_request(
        self,
        request_type: str = verifier.LAYER_BLOB_REQUEST,
        digest: str | None = None,
    ) -> Request:
        expected_digest = digest or self.digest
        request = Request(
            f"https://ghcr.io/v2/{self.image_path}/blobs/{expected_digest}"
        )
        request._oci_request_type = request_type
        request._oci_expected_digest = expected_digest
        request._oci_redirect_count = 0
        return request

    def test_valid_blob_redirect_and_authorization_is_never_forwarded(self) -> None:
        destination = self.signed_blob_url()
        verifier.validate_blob_redirect(destination, self.digest)
        request = self.source_blob_request()
        request.add_header("Authorization", "Bearer must-not-cross-origin")
        request.add_unredirected_header(
            "Authorization",
            "Bearer must-not-cross-origin",
        )
        redirected = verifier.SafeRedirectHandler(
            self.image_path
        ).redirect_request(
            request,
            None,
            307,
            "Temporary Redirect",
            Message(),
            destination,
        )
        self.assertIsNone(redirected.get_header("Authorization"))
        self.assertFalse(
            any(
                name.lower() == "authorization"
                for name, _ in redirected.header_items()
            )
        )

    def test_redirect_downgrade_cross_origin_query_and_fragment_are_rejected(self) -> None:
        valid = self.signed_blob_url()
        invalid_destinations = {
            "downgrade": valid.replace("https://", "http://", 1),
            "cross-origin": valid.replace(
                "pkg-containers.githubusercontent.com",
                "attacker.invalid",
                1,
            ),
            "signed-query": self.signed_blob_url(sp="w"),
            "fragment": f"{valid}#credential-fragment",
        }
        for name, destination in invalid_destinations.items():
            with self.subTest(name=name):
                with self.assertRaises(verifier.RedirectPolicyError):
                    verifier.validate_blob_redirect(destination, self.digest)

        source = (
            f"https://ghcr.io/v2/{self.image_path}/manifests/{self.digest}"
        )
        for suffix in ("?tag=latest", "#fragment"):
            with self.subTest(initial_suffix=suffix):
                with self.assertRaises(verifier.RedirectPolicyError):
                    verifier.validate_initial_request(
                        f"{source}{suffix}",
                        verifier.INDEX_REQUEST,
                        self.digest,
                        self.image_path,
                    )

    def test_redirect_count_is_bounded(self) -> None:
        request = self.source_blob_request()
        request._oci_redirect_count = verifier.MAX_REDIRECTS
        with self.assertRaises(verifier.RedirectPolicyError):
            verifier.SafeRedirectHandler(self.image_path).redirect_request(
                request,
                None,
                307,
                "Temporary Redirect",
                Message(),
                self.signed_blob_url(),
            )

    def test_ghcr1_redirect_is_only_for_canonical_empty_config(self) -> None:
        destination = self.signed_blob_url(
            digest=verifier.EMPTY_JSON_DIGEST,
            bucket="ghcr1",
        )
        config_request = self.source_blob_request(
            verifier.CONFIG_BLOB_REQUEST,
            verifier.EMPTY_JSON_DIGEST,
        )
        config_request.add_unredirected_header(
            "Authorization",
            "Bearer must-not-cross-origin",
        )
        redirected = verifier.SafeRedirectHandler(
            self.image_path
        ).redirect_request(
            config_request,
            None,
            307,
            "Temporary Redirect",
            Message(),
            destination,
        )
        self.assertEqual(redirected.full_url, destination)
        self.assertIsNone(redirected.get_header("Authorization"))

        layer_request = self.source_blob_request(
            verifier.LAYER_BLOB_REQUEST,
            verifier.EMPTY_JSON_DIGEST,
        )
        with self.assertRaises(verifier.RedirectPolicyError):
            verifier.SafeRedirectHandler(self.image_path).redirect_request(
                layer_request,
                None,
                307,
                "Temporary Redirect",
                Message(),
                destination,
            )

        other_digest_destination = self.signed_blob_url(
            digest=self.digest,
            bucket="ghcr1",
        )
        other_config_request = self.source_blob_request(
            verifier.CONFIG_BLOB_REQUEST,
            self.digest,
        )
        with self.assertRaises(verifier.RedirectPolicyError):
            verifier.SafeRedirectHandler(self.image_path).redirect_request(
                other_config_request,
                None,
                307,
                "Temporary Redirect",
                Message(),
                other_digest_destination,
            )


class OutputAndCliTest(unittest.TestCase):
    def assert_no_artifacts(self, directory: Path) -> None:
        self.assertFalse((directory / "provenance.jsonl").exists())
        self.assertEqual(
            list(directory.glob(".provenance.*.jsonl.tmp")),
            [],
        )

    def test_atomic_output_contains_exactly_two_canonical_lines(self) -> None:
        graph = build_graph()
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            output = directory / "provenance.jsonl"
            statements = verifier.verify_and_write(
                graph.context,
                graph.transport,
                output,
            )
            lines = output.read_text(encoding="utf-8").splitlines()
            self.assertEqual(len(lines), 2)
            self.assertEqual(
                [json.loads(line) for line in lines],
                [statement for _, statement in statements],
            )
            self.assertEqual(
                list(directory.glob(".provenance.*.jsonl.tmp")),
                [],
            )

    def test_verification_failure_leaves_no_final_or_temp_output(self) -> None:
        graph = build_graph(
            attestation_mode="modern-inline",
            statement_mutator=lambda platform, statement: (
                statement.__setitem__("predicateType", "wrong")
                if platform == "linux/arm64"
                else None
            )
        )
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            with self.assertRaises(verifier.PolicyError):
                verifier.verify_and_write(
                    graph.context,
                    graph.transport,
                    directory / "provenance.jsonl",
                )
            self.assert_no_artifacts(directory)

    def test_replace_failure_removes_temporary_output(self) -> None:
        graph = build_graph()
        statements = verifier.verify_oci_provenance(
            graph.context,
            graph.transport,
        )
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            with mock.patch.object(
                verifier.os,
                "replace",
                side_effect=OSError("signed-url?sig=must-not-leak"),
            ):
                with self.assertRaises(verifier.OutputError) as caught:
                    verifier.write_provenance_atomic(
                        statements,
                        directory / "provenance.jsonl",
                    )
            self.assertEqual(str(caught.exception), "cannot publish provenance output")
            self.assert_no_artifacts(directory)

    def test_preexisting_output_is_not_overwritten(self) -> None:
        graph = build_graph()
        statements = verifier.verify_oci_provenance(
            graph.context,
            graph.transport,
        )
        with tempfile.TemporaryDirectory() as directory_name:
            output = Path(directory_name) / "provenance.jsonl"
            output.write_text("sentinel\n", encoding="utf-8")
            with self.assertRaises(verifier.OutputError):
                verifier.write_provenance_atomic(statements, output)
            self.assertEqual(output.read_text(encoding="utf-8"), "sentinel\n")

    def test_cli_exit_categories_and_redacted_internal_failure(self) -> None:
        graph = build_graph()
        with tempfile.TemporaryDirectory() as directory_name:
            stdout = io.StringIO()
            stderr = io.StringIO()
            old_directory = Path.cwd()
            os.chdir(directory_name)
            try:
                with mock.patch.object(
                    verifier,
                    "UrllibRegistryTransport",
                    return_value=graph.transport,
                ), redirect_stdout(stdout), redirect_stderr(stderr):
                    status = verifier.main(verifier_args(graph.context), {})
            finally:
                os.chdir(old_directory)
            self.assertEqual(status, verifier.ExitCode.SUCCESS)
            self.assertIn("2 SLSA v1 platform statements", stdout.getvalue())
            self.assertEqual(stderr.getvalue(), "")

        bad_context = replace(graph.context, image_ref="ghcr.io/example:latest")
        with redirect_stderr(io.StringIO()):
            status = verifier.main(verifier_args(bad_context), {})
        self.assertEqual(status, verifier.ExitCode.POLICY)

        class ExplodingTransport:
            def fetch(self, *args: Any, **kwargs: Any) -> Any:
                raise RuntimeError(
                    "token=credential-value&sig=signed-query-value"
                )

        stderr = io.StringIO()
        with mock.patch.object(
            verifier,
            "UrllibRegistryTransport",
            return_value=ExplodingTransport(),
        ), redirect_stderr(stderr):
            status = verifier.main(verifier_args(graph.context), {})
        self.assertEqual(status, verifier.ExitCode.INTERNAL)
        self.assertNotIn("credential-value", stderr.getvalue())
        self.assertNotIn("signed-query-value", stderr.getvalue())
        self.assertNotIn("RuntimeError", stderr.getvalue())

    def test_cli_usage_is_exit_two_and_rejected_values_are_redacted(self) -> None:
        missing_stderr = io.StringIO()
        with redirect_stderr(missing_stderr):
            with self.assertRaises(SystemExit) as caught:
                verifier.main([], {})
        self.assertEqual(caught.exception.code, verifier.ExitCode.USAGE)
        self.assertEqual(
            missing_stderr.getvalue(),
            "release-provenance: invalid command-line arguments; "
            "run with --help\n",
        )

        graph = build_graph()
        rejected_stderr = io.StringIO()
        sensitive_value = "token=fake-secret&sig=fake-signed-query"
        with redirect_stderr(rejected_stderr):
            with self.assertRaises(SystemExit) as caught:
                verifier.main(
                    [
                        *verifier_args(graph.context),
                        "--unexpected",
                        sensitive_value,
                    ],
                    {},
                )
        self.assertEqual(caught.exception.code, verifier.ExitCode.USAGE)
        self.assertNotIn("fake-secret", rejected_stderr.getvalue())
        self.assertNotIn("fake-signed-query", rejected_stderr.getvalue())
        self.assertNotIn("--unexpected", rejected_stderr.getvalue())

    def test_import_is_side_effect_free(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            environment = os.environ.copy()
            environment["PYTHONDONTWRITEBYTECODE"] = "1"
            environment["GH_TOKEN"] = "import-must-not-use-token"
            result = subprocess.run(
                [
                    sys.executable,
                    "-c",
                    (
                        "import runpy; "
                        f"runpy.run_path({str(MODULE_PATH)!r}, "
                        "run_name='import_only')"
                    ),
                ],
                cwd=directory_name,
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, "")
            self.assertEqual(result.stderr, "")
            self.assertEqual(list(Path(directory_name).iterdir()), [])


if __name__ == "__main__":
    unittest.main()

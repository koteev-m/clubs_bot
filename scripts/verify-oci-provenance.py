#!/usr/bin/env python3
"""Verify a signed GHCR OCI index and its embedded SLSA v1 provenance.

The preceding workflow step verifies the Cosign signature.  This module then
verifies the immutable OCI graph and the BuildKit provenance bound to that
signed digest.  Importing the module performs no I/O; network and filesystem
effects live behind explicit transport and output boundaries.
"""

from __future__ import annotations

import argparse
import base64
import binascii
from dataclasses import dataclass
from email.message import Message
from enum import IntEnum
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from typing import Any, Mapping, NoReturn, Protocol, Sequence
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qsl, quote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


OCI_INDEX = "application/vnd.oci.image.index.v1+json"
OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json"
OCI_CONFIG = "application/vnd.oci.image.config.v1+json"
OCI_EMPTY_CONFIG = "application/vnd.oci.empty.v1+json"
OCI_IMAGE_LAYERS = {
    "application/vnd.oci.image.layer.v1.tar",
    "application/vnd.oci.image.layer.v1.tar+gzip",
    "application/vnd.oci.image.layer.v1.tar+zstd",
    "application/vnd.oci.image.layer.nondistributable.v1.tar",
    "application/vnd.oci.image.layer.nondistributable.v1.tar+gzip",
    "application/vnd.oci.image.layer.nondistributable.v1.tar+zstd",
}
DOCKER_MANIFEST = "application/vnd.docker.distribution.manifest.v2+json"
DOCKER_CONFIG = "application/vnd.docker.container.image.v1+json"
DOCKER_IMAGE_LAYERS = {
    "application/vnd.docker.image.rootfs.diff.tar.gzip",
    "application/vnd.docker.image.rootfs.foreign.diff.tar.gzip",
}
PLATFORM_MANIFEST_TYPES = {OCI_MANIFEST, DOCKER_MANIFEST}
PLATFORM_CONFIG_TYPES = {
    OCI_MANIFEST: OCI_CONFIG,
    DOCKER_MANIFEST: DOCKER_CONFIG,
}
PLATFORM_LAYER_TYPES = {
    OCI_MANIFEST: OCI_IMAGE_LAYERS,
    DOCKER_MANIFEST: DOCKER_IMAGE_LAYERS,
}
IN_TOTO = "application/vnd.in-toto+json"
DOCKER_ATTESTATION_ARTIFACT = (
    "application/vnd.docker.attestation.manifest.v1+json"
)
EMPTY_JSON_BYTES = b"{}"
EMPTY_JSON_DIGEST = (
    "sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
)
EMPTY_JSON_SIZE = 2
EMPTY_JSON_DATA = "e30="
IN_TOTO_STATEMENT = "https://in-toto.io/Statement/v1"
SLSA_PROVENANCE = "https://slsa.dev/provenance/v1"
BUILDKIT_BUILD_TYPE = (
    "https://github.com/moby/buildkit/blob/master/"
    "docs/attestations/slsa-definitions.md"
)
EXPECTED_PLATFORMS = frozenset({"linux/amd64", "linux/arm64"})
DIGEST = re.compile(r"sha256:([0-9a-f]{64})\Z")
COMMIT_SHA = re.compile(r"[0-9a-f]{40}\Z")
REGISTRY_ORIGIN = ("https", "ghcr.io", 443)
BLOB_CDN_ORIGIN = (
    "https",
    "pkg-containers.githubusercontent.com",
    443,
)
BLOB_CDN_QUERY_KEYS = frozenset(
    {
        "hmac",
        "se",
        "sig",
        "ske",
        "skoid",
        "sks",
        "skt",
        "sktid",
        "skv",
        "sp",
        "spr",
        "sr",
        "sv",
    }
)
TOKEN_REQUEST = "token"
INDEX_REQUEST = "index-manifest"
PLATFORM_REQUEST = "platform-manifest"
ATTESTATION_REQUEST = "attestation-manifest"
CONFIG_BLOB_REQUEST = "attestation-config-blob"
LAYER_BLOB_REQUEST = "attestation-layer-blob"
MANIFEST_REQUESTS = frozenset(
    {INDEX_REQUEST, PLATFORM_REQUEST, ATTESTATION_REQUEST}
)
BLOB_REQUESTS = frozenset({CONFIG_BLOB_REQUEST, LAYER_BLOB_REQUEST})
MAX_REDIRECTS = 1
OUTPUT_PATH = Path("provenance.jsonl")


class ExitCode(IntEnum):
    """Stable CLI failure categories."""

    SUCCESS = 0
    USAGE = 2
    POLICY = 3
    NETWORK = 4
    OUTPUT = 5
    INTERNAL = 70
    INTERRUPTED = 130


class VerifierError(Exception):
    """Expected, already-redacted verifier failure."""

    exit_code = ExitCode.POLICY


class PolicyError(VerifierError):
    pass


class NetworkError(VerifierError):
    exit_code = ExitCode.NETWORK


class OutputError(VerifierError):
    exit_code = ExitCode.OUTPUT


class RedirectPolicyError(Exception):
    """A URL or redirect did not satisfy the fixed registry policy."""


class RedactingArgumentParser(argparse.ArgumentParser):
    """Keep rejected command-line values out of workflow diagnostics."""

    def error(self, message: str) -> NoReturn:
        del message
        self.exit(
            int(ExitCode.USAGE),
            "release-provenance: invalid command-line arguments; "
            "run with --help\n",
        )


@dataclass(frozen=True)
class VerificationContext:
    image_ref: str
    repository: str
    expected_ref: str
    expected_sha: str
    run_id: str
    run_attempt: str
    workflow_ref: str
    workflow_name: str
    event_name: str

    @property
    def image_path(self) -> str:
        return f"{self.repository}/app-bot"

    @property
    def repository_url(self) -> str:
        return f"https://ghcr.io/v2/{quote(self.image_path, safe='/')}"


class RegistryTransport(Protocol):
    def fetch(
        self,
        image_path: str,
        url: str,
        request_type: str,
        expected_digest: str | None = None,
        authorization: str | None = None,
        accept: str | None = None,
    ) -> tuple[bytes, Message]:
        """Fetch one policy-scoped registry object."""


def reject(message: str) -> None:
    raise PolicyError(message)


def canonical_secure_url(url: str) -> tuple[Any, tuple[str, str, int]]:
    try:
        parsed = urlsplit(url)
        hostname = parsed.hostname
        port = parsed.port
        username = parsed.username
        password = parsed.password
    except (TypeError, ValueError) as error:
        raise RedirectPolicyError from error
    if (
        parsed.scheme.lower() != "https"
        or hostname is None
        or username is not None
        or password is not None
        or parsed.fragment
        or "#" in url
    ):
        raise RedirectPolicyError
    effective_port = 443 if port is None else port
    return parsed, (
        parsed.scheme.lower(),
        hostname.lower(),
        effective_port,
    )


def unique_query(query: str, expected_count: int) -> dict[str, str]:
    if not query or query.count("&") + 1 != expected_count:
        raise RedirectPolicyError
    try:
        pairs = parse_qsl(
            query,
            keep_blank_values=True,
            strict_parsing=True,
        )
    except (UnicodeError, ValueError) as error:
        raise RedirectPolicyError from error
    if (
        len(pairs) != expected_count
        or len({name for name, _ in pairs}) != expected_count
        or any(not name or not value for name, value in pairs)
    ):
        raise RedirectPolicyError
    return dict(pairs)


def validate_initial_request(
    url: str,
    request_type: str,
    expected_digest: str | None,
    image_path: str,
) -> None:
    parsed, origin = canonical_secure_url(url)
    if origin != REGISTRY_ORIGIN:
        raise RedirectPolicyError
    if request_type == TOKEN_REQUEST:
        if expected_digest is not None or parsed.path != "/token":
            raise RedirectPolicyError
        query = unique_query(parsed.query, 2)
        if query != {
            "service": "ghcr.io",
            "scope": f"repository:{image_path}:pull",
        }:
            raise RedirectPolicyError
        return
    if request_type not in MANIFEST_REQUESTS | BLOB_REQUESTS:
        raise RedirectPolicyError
    if not isinstance(expected_digest, str) or DIGEST.fullmatch(
        expected_digest
    ) is None:
        raise RedirectPolicyError
    object_type = "manifests" if request_type in MANIFEST_REQUESTS else "blobs"
    expected_path = (
        f"/v2/{quote(image_path, safe='/')}/"
        f"{object_type}/{expected_digest}"
    )
    if parsed.path != expected_path or parsed.query:
        raise RedirectPolicyError


def validate_blob_redirect(
    url: str,
    expected_digest: str,
    request_type: str = LAYER_BLOB_REQUEST,
) -> None:
    parsed, origin = canonical_secure_url(url)
    if (
        origin != BLOB_CDN_ORIGIN
        or request_type not in BLOB_REQUESTS
        or not isinstance(expected_digest, str)
        or DIGEST.fullmatch(expected_digest) is None
    ):
        raise RedirectPolicyError
    standard_path = re.compile(
        rf"/ghcrblobs[0-9]{{2}}/blobs/{re.escape(expected_digest)}\Z"
    )
    empty_config_path = (
        request_type == CONFIG_BLOB_REQUEST
        and expected_digest == EMPTY_JSON_DIGEST
        and parsed.path == f"/ghcr1/blobs/{EMPTY_JSON_DIGEST}"
    )
    if standard_path.fullmatch(parsed.path) is None and not empty_config_path:
        raise RedirectPolicyError
    query = unique_query(parsed.query, len(BLOB_CDN_QUERY_KEYS))
    if set(query) != BLOB_CDN_QUERY_KEYS or (
        query["sp"] != "r"
        or query["spr"] != "https"
        or query["sr"] != "b"
        or query["sks"] != "b"
    ):
        raise RedirectPolicyError
    timestamp = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z\Z")
    uuid = re.compile(
        r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
        r"[0-9a-f]{4}-[0-9a-f]{12}\Z"
    )
    date = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}\Z")
    if (
        any(
            timestamp.fullmatch(query[name]) is None
            for name in ("se", "ske", "skt")
        )
        or any(
            uuid.fullmatch(query[name]) is None
            for name in ("skoid", "sktid")
        )
        or any(
            date.fullmatch(query[name]) is None
            for name in ("skv", "sv")
        )
        or re.fullmatch(r"[0-9a-f]{64}", query["hmac"]) is None
        or re.fullmatch(r"[A-Za-z0-9+/=_-]+", query["sig"]) is None
    ):
        raise RedirectPolicyError


class SafeRedirectHandler(HTTPRedirectHandler):
    """Allow one digest-bound GHCR blob redirect and strip credentials."""

    def __init__(self, image_path: str) -> None:
        super().__init__()
        self.image_path = image_path

    def redirect_request(
        self,
        request: Request,
        fp: Any,
        code: int,
        message: str,
        headers: Message,
        new_url: str,
    ) -> Request:
        request_type = getattr(request, "_oci_request_type", None)
        expected_digest = getattr(request, "_oci_expected_digest", None)
        redirect_count = getattr(request, "_oci_redirect_count", None)
        if (
            request_type not in BLOB_REQUESTS
            or type(redirect_count) is not int
            or redirect_count >= MAX_REDIRECTS
            or code != 307
            or request.get_method() != "GET"
            or request.data is not None
        ):
            raise RedirectPolicyError
        validate_initial_request(
            request.full_url,
            request_type,
            expected_digest,
            self.image_path,
        )
        validate_blob_redirect(new_url, expected_digest, request_type)
        redirected = super().redirect_request(
            request,
            fp,
            code,
            message,
            headers,
            new_url,
        )
        if (
            redirected is None
            or redirected.get_method() != "GET"
            or redirected.data is not None
        ):
            raise RedirectPolicyError
        redirected.remove_header("Authorization")
        redirected._oci_request_type = request_type
        redirected._oci_expected_digest = expected_digest
        redirected._oci_redirect_count = redirect_count + 1
        return redirected


class UrllibRegistryTransport:
    """The only production network boundary."""

    def __init__(self, image_path: str, timeout_seconds: int = 60) -> None:
        self.image_path = image_path
        self.timeout_seconds = timeout_seconds
        self.opener = build_opener(SafeRedirectHandler(image_path))

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
            raise NetworkError("registry request rejected by policy")
        try:
            validate_initial_request(
                url,
                request_type,
                expected_digest,
                image_path,
            )
        except RedirectPolicyError as error:
            raise NetworkError("registry request rejected by policy") from error
        request = Request(url)
        request._oci_request_type = request_type
        request._oci_expected_digest = expected_digest
        request._oci_redirect_count = 0
        if authorization is not None:
            request.add_unredirected_header("Authorization", authorization)
        if accept is not None:
            request.add_header("Accept", accept)
        try:
            with self.opener.open(
                request,
                timeout=self.timeout_seconds,
            ) as response:
                status = getattr(response, "status", None)
                if type(status) is not int or not 200 <= status < 300:
                    raise NetworkError(
                        "registry response did not have a successful HTTP status"
                    )
                return response.read(), response.headers
        except RedirectPolicyError as error:
            raise NetworkError("registry redirect rejected by policy") from error
        except HTTPError as error:
            raise NetworkError(
                f"registry request failed with HTTP {error.code}"
            ) from error
        except NetworkError:
            raise
        except (URLError, TimeoutError, OSError) as error:
            raise NetworkError("registry request failed") from error


def parse_object(raw: bytes, description: str) -> dict[str, Any]:
    try:
        value = json.loads(raw)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise PolicyError(f"{description} is not valid JSON") from error
    if not isinstance(value, dict):
        reject(f"{description} must be a JSON object")
    return value


def digest_hex(value: Any, description: str) -> str:
    if not isinstance(value, str):
        reject(f"{description} digest must be a string")
    match = DIGEST.fullmatch(value)
    if match is None:
        reject(f"{description} digest is not canonical sha256")
    return match.group(1)


def descriptor_size(descriptor: Mapping[str, Any], description: str) -> int:
    expected_size = descriptor.get("size")
    if type(expected_size) is not int or expected_size <= 0:
        reject(f"{description} size is invalid")
    return expected_size


def validate_bytes(
    raw: bytes,
    descriptor: Mapping[str, Any],
    description: str,
) -> None:
    expected_hex = digest_hex(descriptor.get("digest"), description)
    expected_size = descriptor_size(descriptor, description)
    if len(raw) != expected_size:
        reject(f"{description} size does not match its descriptor")
    if hashlib.sha256(raw).hexdigest() != expected_hex:
        reject(f"{description} content digest does not match its descriptor")


def validate_descriptor(
    descriptor: Any,
    description: str,
    supported_media_types: set[str] | frozenset[str],
) -> str:
    if not isinstance(descriptor, dict):
        reject(f"{description} must be an object")
    media_type = descriptor.get("mediaType")
    if media_type not in supported_media_types:
        reject(f"{description} media type is not supported")
    digest_hex(descriptor.get("digest"), description)
    descriptor_size(descriptor, description)
    return media_type


def verify_modern_empty_config(
    config: Any,
    context: VerificationContext,
    transport: RegistryTransport,
    authorization: str,
) -> None:
    """Verify the exact OCI empty descriptor used by BuildKit artifacts."""

    if not isinstance(config, dict):
        reject("modern attestation empty config descriptor must be an object")
    required_keys = {"mediaType", "digest", "size"}
    if set(config) not in (required_keys, required_keys | {"data"}):
        reject("modern attestation empty config descriptor has unexpected fields")
    if config.get("mediaType") != OCI_EMPTY_CONFIG:
        reject("modern attestation empty config media type is invalid")
    if config.get("digest") != EMPTY_JSON_DIGEST:
        reject("modern attestation empty config digest is invalid")
    if type(config.get("size")) is not int or config.get("size") != (
        EMPTY_JSON_SIZE
    ):
        reject("modern attestation empty config size is invalid")

    if "data" in config:
        encoded = config["data"]
        if not isinstance(encoded, str) or len(encoded) != len(EMPTY_JSON_DATA):
            reject("modern attestation config data is not strict base64")
        try:
            config_raw = base64.b64decode(encoded, validate=True)
        except (binascii.Error, ValueError) as error:
            raise PolicyError(
                "modern attestation config data is not strict base64"
            ) from error
        if base64.b64encode(config_raw).decode("ascii") != encoded:
            reject("modern attestation config data is not canonical base64")
    else:
        config_raw, _ = transport.fetch(
            context.image_path,
            f"{context.repository_url}/blobs/{EMPTY_JSON_DIGEST}",
            CONFIG_BLOB_REQUEST,
            expected_digest=EMPTY_JSON_DIGEST,
            authorization=authorization,
        )

    if config_raw != EMPTY_JSON_BYTES:
        reject("modern attestation config data is not exact empty JSON")
    validate_bytes(config_raw, config, "modern attestation empty config")


def require_content_digest(
    headers: Message,
    expected_digest: str,
    description: str,
) -> None:
    values = headers.get_all("Docker-Content-Digest")
    if values is None:
        reject(f"{description} response is missing Docker-Content-Digest")
    if len(values) != 1:
        reject(
            f"{description} response must have exactly one "
            "Docker-Content-Digest"
        )
    actual_digest = values[0]
    digest_hex(actual_digest, f"{description} response")
    if actual_digest != expected_digest:
        reject(f"registry did not confirm the requested {description} digest")


def require_content_type(
    headers: Message,
    expected_media_type: str,
    description: str,
) -> None:
    actual_header = headers.get("Content-Type")
    if actual_header is None:
        reject(f"{description} response is missing Content-Type")
    actual_media_type = headers.get_content_type()
    if actual_media_type != expected_media_type:
        reject(
            f"{description} response Content-Type does not match its descriptor"
        )


def require_mapping(value: Any, description: str) -> dict[str, Any]:
    if not isinstance(value, dict) or not value:
        reject(f"{description} must be a non-empty object")
    return value


def nested(mapping: Mapping[str, Any], description: str, *keys: str) -> Any:
    value: Any = mapping
    for key in keys:
        if not isinstance(value, dict) or key not in value:
            reject(f"{description} is missing {'.'.join(keys)}")
        value = value[key]
    return value


def validate_context(context: VerificationContext) -> str:
    values = {
        "image reference": context.image_ref,
        "repository": context.repository,
        "ref": context.expected_ref,
        "run ID": context.run_id,
        "run attempt": context.run_attempt,
        "workflow ref": context.workflow_ref,
        "workflow name": context.workflow_name,
        "event name": context.event_name,
    }
    for description, value in values.items():
        if not value:
            reject(f"{description} is empty")
    image_match = re.fullmatch(
        rf"ghcr\.io/{re.escape(context.image_path)}@"
        r"(sha256:[0-9a-f]{64})",
        context.image_ref,
    )
    if image_match is None:
        reject("image reference is not the expected immutable GHCR digest")
    if COMMIT_SHA.fullmatch(context.expected_sha) is None:
        reject("expected SHA is not a canonical commit SHA")
    expected_workflow_ref = (
        f"{context.repository}/.github/workflows/docker-publish.yml@"
        f"{context.expected_ref}"
    )
    if context.workflow_ref != expected_workflow_ref:
        reject("workflow ref is not the canonical publisher workflow")
    return image_match.group(1)


def verify_oci_provenance(
    context: VerificationContext,
    transport: RegistryTransport,
    *,
    github_token: str = "",
    actor: str = "",
) -> list[tuple[str, dict[str, Any]]]:
    """Verify the complete OCI/SLSA graph and return two platform statements."""

    index_digest = validate_context(context)
    token_authorization = None
    if github_token:
        if not actor:
            reject("registry actor is empty while the registry token is present")
        basic = base64.b64encode(
            f"{actor}:{github_token}".encode("utf-8")
        ).decode("ascii")
        token_authorization = f"Basic {basic}"
    token_query = urlencode(
        {
            "service": "ghcr.io",
            "scope": f"repository:{context.image_path}:pull",
        }
    )
    token_raw, _ = transport.fetch(
        context.image_path,
        f"https://ghcr.io/token?{token_query}",
        TOKEN_REQUEST,
        authorization=token_authorization,
    )
    token_document = parse_object(token_raw, "GHCR token response")
    registry_token = token_document.get("token")
    if not isinstance(registry_token, str) or not registry_token:
        reject("GHCR token response has no bearer token")
    authorization = f"Bearer {registry_token}"

    index_raw, index_headers = transport.fetch(
        context.image_path,
        f"{context.repository_url}/manifests/{index_digest}",
        INDEX_REQUEST,
        expected_digest=index_digest,
        authorization=authorization,
        accept=OCI_INDEX,
    )
    require_content_digest(index_headers, index_digest, "image index")
    require_content_type(index_headers, OCI_INDEX, "image index")
    index_hex = digest_hex(index_digest, "image index")
    if hashlib.sha256(index_raw).hexdigest() != index_hex:
        reject("downloaded image index does not match the signed digest")
    index = parse_object(index_raw, "image index")
    if index.get("schemaVersion") != 2 or index.get("mediaType") != OCI_INDEX:
        reject("signed subject is not an OCI image index")
    manifests = index.get("manifests")
    if not isinstance(manifests, list) or not manifests:
        reject("signed OCI index has no manifests")

    platform_descriptors: dict[str, dict[str, Any]] = {}
    provenance_descriptors: list[dict[str, Any]] = []
    for descriptor in manifests:
        if not isinstance(descriptor, dict):
            reject("OCI index contains a malformed descriptor")
        platform = descriptor.get("platform")
        if not isinstance(platform, dict):
            reject("OCI index descriptor has no platform")
        platform_name = f"{platform.get('os')}/{platform.get('architecture')}"
        annotations = descriptor.get("annotations", {})
        if not isinstance(annotations, dict):
            reject("OCI index descriptor annotations are malformed")
        reference_type = annotations.get("vnd.docker.reference.type")
        if reference_type == "attestation-manifest":
            if platform_name != "unknown/unknown":
                reject("attestation manifest must use unknown/unknown platform")
            validate_descriptor(
                descriptor,
                "attestation manifest descriptor",
                {OCI_MANIFEST},
            )
            provenance_descriptors.append(descriptor)
        elif platform_name in EXPECTED_PLATFORMS:
            if annotations.get("vnd.docker.reference.digest") is not None:
                reject("image manifest has an attestation reference annotation")
            if platform_name in platform_descriptors:
                reject(f"duplicate image manifest for {platform_name}")
            validate_descriptor(
                descriptor,
                f"{platform_name} manifest descriptor",
                PLATFORM_MANIFEST_TYPES,
            )
            platform_descriptors[platform_name] = descriptor
        else:
            reject(f"unexpected OCI index descriptor platform: {platform_name}")

    if set(platform_descriptors) != EXPECTED_PLATFORMS:
        reject("OCI index does not contain exactly amd64 and arm64 images")
    if len(provenance_descriptors) != len(EXPECTED_PLATFORMS):
        reject(
            "OCI index does not contain exactly one provenance manifest per platform"
        )

    verified_platform_records: list[tuple[str, str]] = []
    for platform_name in sorted(EXPECTED_PLATFORMS):
        platform_descriptor = platform_descriptors[platform_name]
        platform_digest = platform_descriptor["digest"]
        platform_media_type = platform_descriptor["mediaType"]
        platform_raw, platform_headers = transport.fetch(
            context.image_path,
            f"{context.repository_url}/manifests/{platform_digest}",
            PLATFORM_REQUEST,
            expected_digest=platform_digest,
            authorization=authorization,
            accept=platform_media_type,
        )
        require_content_digest(
            platform_headers,
            platform_digest,
            f"{platform_name} manifest",
        )
        require_content_type(
            platform_headers,
            platform_media_type,
            f"{platform_name} manifest",
        )
        validate_bytes(
            platform_raw,
            platform_descriptor,
            f"{platform_name} manifest",
        )
        platform_manifest = parse_object(
            platform_raw,
            f"{platform_name} manifest",
        )
        if platform_manifest.get("schemaVersion") != 2:
            reject(f"{platform_name} manifest schemaVersion is not 2")
        if platform_manifest.get("mediaType") != platform_media_type:
            reject(
                f"{platform_name} manifest media type does not match its descriptor"
            )
        platform_config = platform_manifest.get("config")
        validate_descriptor(
            platform_config,
            f"{platform_name} config descriptor",
            {PLATFORM_CONFIG_TYPES[platform_media_type]},
        )
        platform_layers = platform_manifest.get("layers")
        if not isinstance(platform_layers, list):
            reject(f"{platform_name} manifest layers must be an array")
        for layer_index, platform_layer in enumerate(platform_layers):
            layer_description = (
                f"{platform_name} layer descriptor {layer_index}"
            )
            if isinstance(platform_layer, dict) and (
                platform_layer.get("mediaType") == IN_TOTO
            ):
                reject(f"{platform_name} manifest is an attestation manifest")
            validate_descriptor(
                platform_layer,
                layer_description,
                PLATFORM_LAYER_TYPES[platform_media_type],
            )
        verified_platform_records.append((platform_name, platform_digest))

    if len(verified_platform_records) != len(EXPECTED_PLATFORMS) or (
        {platform for platform, _ in verified_platform_records}
        != EXPECTED_PLATFORMS
    ):
        reject("verified platform manifest set is incomplete")
    verified_platform_digests = dict(verified_platform_records)
    verified_digest_platforms = {
        digest: platform for platform, digest in verified_platform_records
    }
    if len(verified_digest_platforms) != len(EXPECTED_PLATFORMS):
        reject("verified platform manifest digests are not unique")

    expected_builder = (
        f"https://github.com/{context.repository}/actions/runs/{context.run_id}/"
        f"attempts/{context.run_attempt}"
    )
    expected_source = f"https://github.com/{context.repository}"
    verified_statements: list[tuple[str, dict[str, Any]]] = []
    claimed_platforms: set[str] = set()
    attestation_modes: set[str] = set()
    for descriptor in provenance_descriptors:
        manifest_digest = descriptor["digest"]
        manifest_raw, manifest_headers = transport.fetch(
            context.image_path,
            f"{context.repository_url}/manifests/{manifest_digest}",
            ATTESTATION_REQUEST,
            expected_digest=manifest_digest,
            authorization=authorization,
            accept=OCI_MANIFEST,
        )
        require_content_digest(
            manifest_headers,
            manifest_digest,
            "attestation manifest",
        )
        require_content_type(
            manifest_headers,
            OCI_MANIFEST,
            "attestation manifest",
        )
        validate_bytes(manifest_raw, descriptor, "attestation manifest")
        manifest = parse_object(manifest_raw, "attestation manifest")
        if (
            type(manifest.get("schemaVersion")) is not int
            or manifest.get("schemaVersion") != 2
            or manifest.get("mediaType") != OCI_MANIFEST
        ):
            reject("attestation object is not an OCI manifest")

        annotations = descriptor["annotations"]
        artifact_type_present = "artifactType" in manifest
        subject_present = "subject" in manifest
        if artifact_type_present or subject_present:
            mode = "modern"
            if manifest.get("artifactType") != DOCKER_ATTESTATION_ARTIFACT:
                reject("modern attestation artifactType is invalid")
            if not subject_present:
                reject("modern attestation subject is missing")
            subject = manifest["subject"]
            if not isinstance(subject, dict) or set(subject) != {
                "mediaType",
                "digest",
                "size",
            }:
                reject(
                    "modern attestation subject is not the canonical "
                    "BuildKit descriptor"
                )
            validate_descriptor(
                subject,
                "modern attestation subject",
                PLATFORM_MANIFEST_TYPES,
            )
            subject_digest = subject["digest"]
            platform_name = verified_digest_platforms.get(subject_digest, "")
            if not platform_name:
                reject(
                    "modern attestation subject digest does not match a "
                    "verified platform image"
                )
            platform_descriptor = platform_descriptors[platform_name]
            if subject.get("mediaType") != platform_descriptor["mediaType"]:
                reject(
                    "modern attestation subject media type does not match the "
                    "verified platform descriptor"
                )
            if subject_digest != platform_descriptor["digest"]:
                reject(
                    "modern attestation subject digest does not match the "
                    "verified platform descriptor"
                )
            if subject.get("size") != platform_descriptor["size"]:
                reject(
                    "modern attestation subject size does not match the "
                    "verified platform descriptor"
                )
            reference_key = "vnd.docker.reference.digest"
            if (
                reference_key in annotations
                and annotations[reference_key] != subject_digest
            ):
                reject(
                    "index attestation reference annotation does not match "
                    "the modern subject digest"
                )
        else:
            mode = "legacy"
            referenced_digest = annotations.get(
                "vnd.docker.reference.digest"
            )
            platform_name = (
                verified_digest_platforms.get(referenced_digest, "")
                if isinstance(referenced_digest, str)
                else ""
            )
            if not platform_name:
                reject(
                    "legacy provenance manifest does not reference a verified "
                    "platform image"
                )

        attestation_modes.add(mode)
        if len(attestation_modes) != 1:
            reject("attestation manifests mix modern and legacy formats")
        if platform_name in claimed_platforms:
            reject(
                "provenance manifests do not cover each verified platform "
                "exactly once"
            )
        claimed_platforms.add(platform_name)
        verified_platform_digest = verified_platform_digests[platform_name]

        config = manifest.get("config")
        layers = manifest.get("layers")
        if not isinstance(layers, list) or len(layers) != 1:
            reject("attestation manifest must contain exactly one layer")
        layer = layers[0]
        if not isinstance(layer, dict) or layer.get("mediaType") != IN_TOTO:
            reject("attestation layer is not an in-toto statement")
        layer_annotations = layer.get("annotations")
        if not isinstance(layer_annotations, dict) or (
            layer_annotations.get("in-toto.io/predicate-type")
            != SLSA_PROVENANCE
        ):
            reject("attestation layer predicate type is not SLSA provenance v1")
        layer_digest = layer.get("digest")
        digest_hex(layer_digest, "attestation layer")

        if mode == "modern":
            verify_modern_empty_config(
                config,
                context,
                transport,
                authorization,
            )
        else:
            if (
                not isinstance(config, dict)
                or config.get("mediaType") != OCI_CONFIG
            ):
                reject("legacy attestation config is not an OCI image config")
            config_digest = config.get("digest")
            digest_hex(config_digest, "legacy attestation config")
            config_raw, _ = transport.fetch(
                context.image_path,
                f"{context.repository_url}/blobs/{config_digest}",
                CONFIG_BLOB_REQUEST,
                expected_digest=config_digest,
                authorization=authorization,
            )
            validate_bytes(config_raw, config, "legacy attestation config")
            config_document = parse_object(
                config_raw,
                "legacy attestation config",
            )
            if (
                config_document.get("architecture") != "unknown"
                or config_document.get("os") != "unknown"
                or config_document.get("config") != {}
            ):
                reject(
                    "legacy attestation config is not the BuildKit "
                    "unknown-platform contract"
                )
            rootfs = config_document.get("rootfs")
            if not isinstance(rootfs, dict) or (
                rootfs.get("type") != "layers"
                or rootfs.get("diff_ids") != [layer_digest]
            ):
                reject(
                    "legacy attestation config does not bind the provenance "
                    "layer"
                )
        statement_raw, _ = transport.fetch(
            context.image_path,
            f"{context.repository_url}/blobs/{layer_digest}",
            LAYER_BLOB_REQUEST,
            expected_digest=layer_digest,
            authorization=authorization,
        )
        validate_bytes(statement_raw, layer, "attestation layer")
        statement = parse_object(statement_raw, "SLSA provenance statement")
        if statement.get("_type") != IN_TOTO_STATEMENT:
            reject("provenance _type is not in-toto Statement v1")
        if statement.get("predicateType") != SLSA_PROVENANCE:
            reject("provenance predicateType is not SLSA provenance v1")
        predicate = require_mapping(
            statement.get("predicate"),
            "SLSA provenance predicate",
        )
        subjects = statement.get("subject")
        if not isinstance(subjects, list) or not subjects:
            reject("SLSA provenance subject is empty")
        expected_subject_prefix = (
            f"pkg:docker/ghcr.io/{context.image_path}@"
        )
        expected_subject_suffix = f"?platform={quote(platform_name, safe='')}"
        expected_sha_subject = (
            f"{expected_subject_prefix}sha-{context.expected_sha[:7]}"
            f"{expected_subject_suffix}"
        )
        subject_names: set[str] = set()
        for subject in subjects:
            if not isinstance(subject, dict):
                reject("SLSA provenance subject is malformed")
            subject_name = subject.get("name")
            if (
                not isinstance(subject_name, str)
                or not subject_name.startswith(expected_subject_prefix)
                or not subject_name.endswith(expected_subject_suffix)
            ):
                reject(
                    "SLSA provenance subject name does not match the platform image"
                )
            tag = subject_name[
                len(expected_subject_prefix) : -len(expected_subject_suffix)
            ]
            if re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}", tag) is None:
                reject("SLSA provenance subject tag is malformed")
            if subject_name in subject_names:
                reject("SLSA provenance subject name is duplicated")
            subject_names.add(subject_name)
            subject_digest = subject.get("digest")
            if not isinstance(subject_digest, dict) or (
                set(subject_digest) != {"sha256"}
            ):
                reject(
                    "SLSA provenance subject must have exactly one sha256 digest"
                )
            if (
                f"sha256:{subject_digest['sha256']}"
                != verified_platform_digest
            ):
                reject(
                    "SLSA provenance subject digest does not match the "
                    "verified platform image"
                )
        if expected_sha_subject not in subject_names:
            reject("SLSA provenance lacks the immutable Git SHA subject")

        build_definition = require_mapping(
            predicate.get("buildDefinition"),
            "SLSA buildDefinition",
        )
        if build_definition.get("buildType") != BUILDKIT_BUILD_TYPE:
            reject("SLSA buildType is not the BuildKit contract")
        builder_id = nested(
            predicate,
            "SLSA builder identity",
            "runDetails",
            "builder",
            "id",
        )
        if builder_id != expected_builder:
            reject("SLSA builder identity does not match this workflow run")
        build_metadata = require_mapping(
            nested(
                build_definition,
                "BuildKit GitHub metadata",
                "internalParameters",
            ),
            "BuildKit GitHub metadata",
        )
        expected_metadata = {
            "github_event_name": context.event_name,
            "github_job": "build-and-push",
            "github_ref": context.expected_ref,
            "github_repository": context.repository,
            "github_run_attempt": context.run_attempt,
            "github_run_id": context.run_id,
            "github_workflow": context.workflow_name,
            "github_workflow_ref": context.workflow_ref,
            "github_workflow_sha": context.expected_sha,
        }
        for key, expected in expected_metadata.items():
            if build_metadata.get(key) != expected:
                reject(f"BuildKit GitHub metadata mismatch: {key}")
        root_args = nested(
            build_definition,
            "BuildKit source metadata",
            "externalParameters",
            "request",
            "root",
            "request",
            "args",
        )
        if not isinstance(root_args, dict):
            reject("BuildKit source metadata args must be an object")
        if root_args.get("vcs:revision") != context.expected_sha:
            reject("BuildKit source revision does not match the expected SHA")
        if root_args.get("vcs:source") != expected_source:
            reject("BuildKit source repository does not match the repository")
        verified_statements.append((platform_name, statement))

    if claimed_platforms != EXPECTED_PLATFORMS or (
        len(verified_statements) != len(EXPECTED_PLATFORMS)
    ):
        reject("verified provenance statement set is incomplete")
    verified_statements.sort(key=lambda item: item[0])
    return verified_statements


def write_provenance_atomic(
    verified_statements: Sequence[tuple[str, Mapping[str, Any]]],
    output: Path = OUTPUT_PATH,
) -> None:
    """Publish canonical JSONL atomically, leaving no temp file on failure."""

    try:
        if os.path.lexists(output):
            raise OutputError("provenance output path already exists")
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=".provenance.",
            suffix=".jsonl.tmp",
            dir=output.parent,
        )
    except OutputError:
        raise
    except OSError as error:
        raise OutputError("cannot create provenance output") from error

    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            for _, statement in verified_statements:
                stream.write(
                    json.dumps(
                        statement,
                        ensure_ascii=False,
                        separators=(",", ":"),
                        sort_keys=True,
                    )
                )
                stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        if os.path.getsize(temporary_name) <= 0:
            raise OutputError("verified provenance output is empty")
        os.replace(temporary_name, output)
    except OutputError:
        raise
    except (OSError, TypeError, ValueError) as error:
        raise OutputError("cannot publish provenance output") from error
    finally:
        try:
            if os.path.exists(temporary_name):
                os.unlink(temporary_name)
        except OSError as error:
            raise OutputError("cannot remove temporary provenance output") from error


def verify_and_write(
    context: VerificationContext,
    transport: RegistryTransport,
    output: Path = OUTPUT_PATH,
    *,
    github_token: str = "",
    actor: str = "",
) -> list[tuple[str, dict[str, Any]]]:
    statements = verify_oci_provenance(
        context,
        transport,
        github_token=github_token,
        actor=actor,
    )
    write_provenance_atomic(statements, output)
    return statements


def build_argument_parser() -> argparse.ArgumentParser:
    parser = RedactingArgumentParser(
        description=(
            "Verify the immutable GHCR OCI graph and embedded SLSA v1 provenance"
        ),
        allow_abbrev=False,
    )
    parser.add_argument("--image-ref", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--ref", dest="expected_ref", required=True)
    parser.add_argument("--sha", dest="expected_sha", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--run-attempt", required=True)
    parser.add_argument("--workflow-ref", required=True)
    parser.add_argument("--workflow-name", required=True)
    parser.add_argument("--event-name", required=True)
    return parser


def main(
    argv: Sequence[str] | None = None,
    environ: Mapping[str, str] | None = None,
) -> int:
    args = build_argument_parser().parse_args(argv)
    context = VerificationContext(
        image_ref=args.image_ref,
        repository=args.repository,
        expected_ref=args.expected_ref,
        expected_sha=args.expected_sha,
        run_id=args.run_id,
        run_attempt=args.run_attempt,
        workflow_ref=args.workflow_ref,
        workflow_name=args.workflow_name,
        event_name=args.event_name,
    )
    environment = os.environ if environ is None else environ
    try:
        validate_context(context)
        transport = UrllibRegistryTransport(context.image_path)
        statements = verify_and_write(
            context,
            transport,
            github_token=environment.get("GH_TOKEN", ""),
            actor=environment.get("REGISTRY_ACTOR", ""),
        )
    except VerifierError as error:
        print(f"release-provenance: {error}", file=sys.stderr)
        return int(error.exit_code)
    except KeyboardInterrupt:
        print("release-provenance: interrupted", file=sys.stderr)
        return int(ExitCode.INTERRUPTED)
    except Exception:
        # Never expose raw exceptions: they can contain credentials or signed URLs.
        print("release-provenance: unexpected internal failure", file=sys.stderr)
        return int(ExitCode.INTERNAL)
    print(
        "release-provenance: verified signed OCI index and "
        f"{len(statements)} SLSA v1 platform statements"
    )
    return int(ExitCode.SUCCESS)


if __name__ == "__main__":
    raise SystemExit(main())

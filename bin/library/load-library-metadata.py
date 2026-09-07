from __future__ import annotations

from copy import deepcopy
from datetime import datetime
from pathlib import Path
from typing import Any, TypedDict
import json
import os

import firebase_admin
from firebase_admin import credentials, storage, firestore
from google.cloud.firestore import Client as FirestoreClient
from google.cloud.storage import Bucket
import google.auth.credentials
import requests


# -----------------------------------
# TYPE DEFINITIONS
# -----------------------------------


class ParameterDefinition(TypedDict):
    key: str
    label: str
    required: bool
    type: str


class CheckRecord(TypedDict, total=False):
    id: str
    evaluationUrl: str
    method: str
    name: str
    description: str
    module: str
    version: str
    inputs: dict[str, Any]
    parameterDefinitions: list[ParameterDefinition]
    inputDefinition: dict[str, Any]


class BenefitCheckDefinition(TypedDict):
    module: str
    name: str
    aliasName: str
    parameters: dict[str, Any]


class BenefitDefinition(TypedDict):
    slug: str
    name: str
    description: str
    checks: list[BenefitCheckDefinition]


# Type aliases for clarity
JsonSchema = dict[str, Any]
OpenAPIComponents = dict[str, JsonSchema]
OpenAPIDocument = dict[str, Any]


class EmulatorCredentials(credentials.Base):
    """Mock credentials for use with Firebase emulators."""

    _mock_credential: google.auth.credentials.Credentials

    def __init__(self) -> None:
        self._mock_credential = google.auth.credentials.AnonymousCredentials()

    def get_credential(self) -> google.auth.credentials.Credentials:
        return self._mock_credential


# -----------------------------------
# CONFIGURATION
# -----------------------------------

# Default to localhost for developer-friendly setup
DEFAULT_LIBRARY_API_URL = "http://localhost:8083"
LIBRARY_API_BASE_URL = os.getenv("LIBRARY_API_BASE_URL", DEFAULT_LIBRARY_API_URL)

# Infer production mode from URL (for versioned URL logic)
IS_PRODUCTION = not (
    "localhost" in LIBRARY_API_BASE_URL or "127.0.0.1" in LIBRARY_API_BASE_URL
)

# Storage bucket defaults - use dev bucket by default
DEFAULT_DEV_BUCKET = "demo-bdt-dev.appspot.com"
DEFAULT_PROD_BUCKET = "benefit-decision-toolkit-play.firebasestorage.app"
STORAGE_BUCKET = os.getenv(
    "GCS_BUCKET_NAME", DEFAULT_PROD_BUCKET if IS_PRODUCTION else DEFAULT_DEV_BUCKET
)

# Log configuration
print(f"========================================")
print(f"Library Metadata Sync Configuration")
print(f"========================================")
print(f"Mode: {'production' if IS_PRODUCTION else 'development'}")
print(f"Library API URL: {LIBRARY_API_BASE_URL}")
print(f"Storage Bucket: {STORAGE_BUCKET}")
print(f"========================================\n")

# -----------------------------------
# INIT FIREBASE
# -----------------------------------

# Point google-cloud-storage SDK at the emulator using the existing Quarkus config variable
storage_host_override = os.getenv("QUARKUS_GOOGLE_CLOUD_STORAGE_HOST_OVERRIDE")
if storage_host_override:
    os.environ["STORAGE_EMULATOR_HOST"] = storage_host_override

firebase_options = {"storageBucket": STORAGE_BUCKET}

if IS_PRODUCTION:
    # Production uses Application Default Credentials
    cred = credentials.ApplicationDefault()
else:
    # Emulators don't need real credentials - use anonymous/mock credentials
    # Set FIRESTORE_EMULATOR_HOST if not already set (standard Firebase emulator env var)
    if not os.getenv("FIRESTORE_EMULATOR_HOST"):
        os.environ["FIRESTORE_EMULATOR_HOST"] = "localhost:8080"

    # Use mock credentials for emulator mode
    cred = EmulatorCredentials()
    firebase_options["projectId"] = os.getenv(
        "QUARKUS_GOOGLE_CLOUD_PROJECT_ID", "demo-bdt-dev"
    )

firebase_admin.initialize_app(cred, firebase_options)

db: FirestoreClient = firestore.client()
bucket: Bucket = storage.bucket()


# --------------------------------------------
# Resolve a $ref inside the components/schemas
# --------------------------------------------


def resolve_ref(ref: str, components: OpenAPIComponents) -> JsonSchema:
    """Resolve a JSON Schema $ref to its target schema."""
    ref_path = ref.replace("#/components/schemas/", "")
    if ref_path not in components:
        return {}
    schema = components[ref_path]

    # Deep copy to avoid mutating original schema
    return deepcopy(schema)


# --------------------------------------------
# Recursively expand schemas and resolve $ref
# --------------------------------------------
def expand_schema(schema: Any, components: OpenAPIComponents) -> Any:
    """Recursively expand all $ref inside a schema node."""
    if not isinstance(schema, dict):
        return schema

    # If the schema is only a $ref, replace it fully
    if "$ref" in schema:
        target = resolve_ref(schema["$ref"], components)
        return expand_schema(target, components)

    expanded: dict[str, Any] = {}
    for key, value in schema.items():
        # Recurse into lists (e.g., 'allOf', 'oneOf')
        if isinstance(value, list):
            expanded[key] = [expand_schema(v, components) for v in value]
            continue

        # Recurse into dict children (e.g., items, properties, etc.)
        if isinstance(value, dict):
            expanded[key] = expand_schema(value, components)
            continue

        # Base case: primitive or unchanged field
        expanded[key] = value

    return expanded


def extract_top_level_inputs(
    schema: JsonSchema, components: OpenAPIComponents
) -> dict[str, JsonSchema]:
    """Return only top-level properties of requestBody schema."""
    expanded = expand_schema(schema, components)

    inputs: dict[str, JsonSchema] = {}
    if expanded.get("type") == "object":
        for prop_name, prop_schema in expanded.get("properties", {}).items():
            # Fully expand each property
            inputs[prop_name] = expand_schema(prop_schema, components)
    return inputs


# --------------------------------------------
# Convert schema to simple {field: type}
# --------------------------------------------
def flatten_schema(schema: JsonSchema) -> dict[str, JsonSchema]:
    """Flatten a nested JSON schema into a flat dictionary with dotted keys."""
    flat: dict[str, JsonSchema] = {}

    def walk(name: str, node: Any) -> None:
        if not isinstance(node, dict):
            return

        node_type = node.get("type")

        # ---------------------------------
        # Primitive types
        # ---------------------------------
        if node_type in ("string", "number", "integer", "boolean"):
            flat[name] = {"type": node_type}
            return

        # ---------------------------------
        # Object
        # ---------------------------------
        if node_type == "object":
            props = node.get("properties", {})

            # Create schema entry for this object
            flat[name] = {"type": "object", "properties": {}}

            # Add detailed properties
            for p_name, p_schema in props.items():
                flat[name]["properties"][p_name] = p_schema

                # Also flatten subproperties
                full_name = f"{name}.{p_name}" if name else p_name
                walk(full_name, p_schema)

            return

        # ---------------------------------
        # Array
        # ---------------------------------
        if node_type == "array":
            items = node.get("items", {})

            # Create array schema entry
            flat[name] = {"type": "array", "items": items}

            # Flatten item structure using name[]
            walk(name + "[]", items)
            return

        # Default fallback
        flat[name] = node

    walk("", schema)
    return flat


# --------------------------------------------
# Process entire OpenAPI document
# --------------------------------------------
def extract_check_records(openapi: OpenAPIDocument, version: str) -> list[CheckRecord]:
    """Extract check records from an OpenAPI document."""
    components: OpenAPIComponents = openapi.get("components", {}).get("schemas", {})
    paths: dict[str, Any] = openapi.get("paths", {})

    output: list[CheckRecord] = []

    for path, methods in paths.items():
        # Only process check endpoints
        if "/checks" not in path:
            continue
        for method_key, details in methods.items():
            method_upper = method_key.upper()

            # Split the URL into parts
            segments = path.strip("/").split("/")

            # Find index of 'checks'
            checks_index = segments.index("checks")

            name = segments[-1]

            module = "/".join(segments[checks_index + 1 : -1])

            check_id = "L-" + module + "-" + name + "-" + version

            entry: CheckRecord = {
                "id": check_id,
                "evaluationUrl": path,
                "method": method_upper,
                "name": name,
                "description": details.get("description", ""),
                "module": module,
                "version": version,
                "inputs": {},
            }

            # ----------------------------------------
            # 1. Path or query parameters
            # ----------------------------------------
            parameters: list[dict[str, Any]] = details.get("parameters", [])
            for p in parameters:
                param_name: str = p["name"]
                dtype: str = p.get("schema", {}).get("type", "unknown")
                entry["inputs"][param_name] = dtype

            # ----------------------------------------
            # 2. Request body parameters
            # ----------------------------------------
            if "requestBody" in details:
                content: dict[str, Any] = details["requestBody"]["content"]

                if "application/json" in content:
                    schema: JsonSchema = content["application/json"].get("schema", {})
                    # Only expand top-level 'parameters' and 'situation'
                    entry["inputs"].update(extract_top_level_inputs(schema, components))

                    output.append(entry)

    return output


def transform_parameters(
    properties_obj: dict[str, JsonSchema],
) -> list[ParameterDefinition]:
    """Convert properties dict to a list of {key, name, type} objects."""
    transformed: list[ParameterDefinition] = []

    for key, val in properties_obj.items():
        # Determine the property's type
        prop_type: str = val.get("type", "object")  # fallback

        # Check for date format - OpenAPI represents FEEL date as { type: "string", format: "date" }
        prop_format: str | None = val.get("format")
        if prop_type == "string" and prop_format == "date":
            prop_type = "date"

        transformed.append(
            {"key": key, "label": key, "required": False, "type": prop_type}
        )

    return transformed


def transform_parameters_format(data: list[CheckRecord]) -> list[CheckRecord]:
    """Transform all `inputs.parameters.properties` in the provided list."""
    for check in data:
        inputs: dict[str, Any] = check.get("inputs", {})
        parameters: dict[str, Any] | None = inputs.get("parameters")

        # Only transform objects that follow the original structure
        if isinstance(parameters, dict) and "properties" in parameters:
            properties_obj: dict[str, JsonSchema] = parameters["properties"]
            new_parameters = transform_parameters(properties_obj)

            # Replace object with the transformed list
            check["parameterDefinitions"] = new_parameters
    return data


def transform_situation_format(data: list[CheckRecord]) -> list[CheckRecord]:
    """Transform all `inputs.situation` in the provided list."""
    for check in data:
        check["inputDefinition"] = check["inputs"]["situation"]
    return data


def build_benefit_records(
    definitions: list[BenefitDefinition], checks: list[CheckRecord], version: str
) -> list[dict[str, Any]]:
    """Build editable benefit templates from the declarative catalog."""
    checks_by_key = {
        (check["module"], check["name"]): check
        for check in checks
    }
    benefits: list[dict[str, Any]] = []

    for definition in definitions:
        benefit_checks: list[dict[str, Any]] = []
        for configured_check in definition["checks"]:
            key = (configured_check["module"], configured_check["name"])
            if key not in checks_by_key:
                raise ValueError(
                    f"Benefit {definition['slug']} references unknown library check "
                    f"{configured_check['module']}/{configured_check['name']}"
                )

            source = checks_by_key[key]
            benefit_checks.append(
                {
                    "checkId": source["id"],
                    "sourceCheckId": source["id"],
                    "checkName": source["name"],
                    "checkVersion": source["version"],
                    "checkModule": source["module"],
                    "evaluationUrl": source["evaluationUrl"],
                    "inputDefinition": source["inputDefinition"],
                    "parameterDefinitions": source.get("parameterDefinitions", []),
                    "parameters": configured_check.get("parameters", {}),
                    "aliasName": configured_check.get("aliasName"),
                }
            )

        benefits.append(
            {
                "id": f"L-benefit-{definition['slug']}-{version}",
                "name": definition["name"],
                "description": definition["description"],
                "checks": benefit_checks,
            }
        )

    return benefits


def load_benefit_definitions() -> list[BenefitDefinition]:
    catalog_path = (
        Path(__file__).resolve().parents[2]
        / "library-api"
        / "src"
        / "main"
        / "resources"
        / "benefits"
        / "catalog.json"
    )
    with catalog_path.open(encoding="utf-8") as catalog_file:
        return json.load(catalog_file)


def save_json_to_storage_and_update_firestore(
    json_string: str,
    firestore_doc_path: str,
    storage_path_field: str = "latestJsonStoragePath",
    export_name: str = "checks",
) -> str:
    """
    Upload JSON string to Firebase Storage and update Firestore
    with the storage path or download URL of the uploaded file.
    """
    from datetime import timezone

    # ---------------------
    # Create filename
    # Example: exported_2025-02-12_14-30-59.json
    # ---------------------
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%d_%H-%M-%S")
    filename = f"LibraryApiSchemaExports/{export_name}_{timestamp}.json"

    # ---------------------
    # Upload to storage
    # ---------------------
    blob = bucket.blob(filename)
    blob.upload_from_string(json_string, content_type="application/json")

    # Get the storage path
    storage_path: str = blob.name

    # ---------------------
    # Update Firestore
    # ---------------------
    doc_ref = db.document(firestore_doc_path)
    doc_ref.set(
        {
            storage_path_field: storage_path,
            "updatedAt": firestore.SERVER_TIMESTAMP,
        },
        merge=True,
    )

    print("Uploaded:", storage_path)
    print("Firestore updated!")

    return storage_path


# --------------------------------------------
# Load your OpenAPI JSON here
# --------------------------------------------
def main() -> None:
    """Main entry point for the library metadata sync script."""
    url: str = f"{LIBRARY_API_BASE_URL}/q/openapi.json"

    print(f"Fetching OpenAPI spec from: {url}")

    # Send a GET request
    response: requests.Response = requests.get(url)

    # Raise an error if the request failed
    response.raise_for_status()  # optional, but good practice

    # Parse JSON
    data: OpenAPIDocument = response.json()

    version: str = data["info"]["version"]

    check_records: list[CheckRecord] = extract_check_records(data, version)
    check_records = transform_parameters_format(check_records)
    check_records = transform_situation_format(check_records)

    for check in check_records:
        check.pop("inputs")  # type: ignore[misc]

    benefit_records = build_benefit_records(
        load_benefit_definitions(), check_records, version
    )

    # Keep the existing checks artifact unchanged for rolling-deploy compatibility.
    checks_json: str = json.dumps(check_records, indent=2, ensure_ascii=False)
    benefits_json: str = json.dumps(benefit_records, indent=2, ensure_ascii=False)

    print("Parsed json")
    print(json.dumps({"checks": check_records, "benefits": benefit_records}, indent=2))

    save_json_to_storage_and_update_firestore(
        checks_json, firestore_doc_path="system/config"
    )
    save_json_to_storage_and_update_firestore(
        benefits_json,
        firestore_doc_path="system/config",
        storage_path_field="latestBenefitsJsonStoragePath",
        export_name="benefits",
    )


if __name__ == "__main__":
    main()

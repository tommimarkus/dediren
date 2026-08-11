package dev.dediren.mcp;

/** The JSON input schemas the registered tools advertise, one constant per tool. */
final class ToolSchemas {
  private ToolSchemas() {}

  static final String WORKSPACE_OPEN =
      """
      {
        "type": "object",
        "properties": {
          "workspace_id": {
            "type": "string",
            "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            "description": "Optional canonical UUIDv4 of an existing, unexpired MCP workspace to resume. Omit to create one."
          }
        }
      }
      """;

  static final String VALIDATE =
      """
      {
        "type": "object",
        "properties": {
          "source": {
            "type": "string",
            "description": "Path to the document to validate (source model or policy JSON), relative to server --root."
          },
          "profile": {
            "type": "string",
            "description": "Optional semantic profile (for example 'archimate' or 'uml'). When set, treats the document as a source model and runs semantic profile validation in addition to schema validation."
          }
        },
        "required": ["source"]
      }
      """;

  static final String BUILD =
      """
      {
        "type": "object",
        "properties": {
          "workspace_id": {
            "type": "string",
            "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            "description": "Canonical UUIDv4 returned by dediren_workspace_open."
          },
          "source": {
            "type": "string",
            "description": "Path to the source JSON, relative to server --root."
          },
          "out": {
            "type": "string",
            "description": "Output directory for generated artifacts, relative to the isolated MCP workspace."
          },
          "views": {
            "type": "array",
            "items": {"type": "string"},
            "description": "View ids to build. Omit to build every view in model order."
          },
          "render_policy": {"type": "string", "description": "Path to a render policy JSON. Selects the SVG lane."},
          "oef_policy": {"type": "string", "description": "Path to an OEF export policy JSON. Selects the ArchiMate OEF lane."},
          "xmi_policy": {"type": "string", "description": "Path to a UML XMI export policy JSON. Selects the UML XMI lane."},
          "emit": {
            "type": "array",
            "items": {"type": "string", "enum": ["layout-request", "layout-result", "render-metadata"]},
            "description": "Optional stage envelopes to also write under 'out', for debugging."
          },
          "package": {
            "type": "string",
            "description": "Path to a package.json declaring models, views, and exports, relative to server --root. Builds the whole package end to end; mutually exclusive with source/out and the per-lane policies."
          },
          "no_export": {
            "type": "boolean",
            "description": "In package mode, suppress the export lanes."
          }
        },
        "required": ["workspace_id"]
      }
      """;

  static final String DIFF =
      """
      {
        "type": "object",
        "properties": {
          "old": {
            "type": "string",
            "description": "Path to the baseline source model, relative to the workspace root."
          },
          "new": {
            "type": "string",
            "description": "Path to the changed source model, relative to the workspace root."
          }
        },
        "required": ["old", "new"]
      }
      """;

  static final String QUERY =
      """
      {
        "type": "object",
        "properties": {
          "source": {
            "type": "string",
            "description": "Path to the source model, relative to the workspace root."
          },
          "kind": {
            "type": "string",
            "enum": ["dependents", "orphans", "view-coverage"],
            "description": "The fixed query to run. 'dependents' also needs 'id'."
          },
          "id": {
            "type": "string",
            "description": "Node id to query. Required when kind is 'dependents'."
          }
        },
        "required": ["source", "kind"]
      }
      """;

  static final String VERIFY =
      """
      {
        "type": "object",
        "properties": {
          "workspace_id": {
            "type": "string",
            "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            "description": "Optional MCP workspace handle. When present, artifacts is relative to that workspace."
          },
          "source": {
            "type": "string",
            "description": "Path to the source model, relative to the workspace root."
          },
          "artifacts": {
            "type": "string",
            "description": "Artifact directory relative to the selected MCP workspace when workspace_id is present, otherwise relative to server --root."
          }
        },
        "required": ["source", "artifacts"]
      }
      """;

  static final String STATUS =
      """
      {
        "type": "object",
        "properties": {
          "workspace_id": {
            "type": "string",
            "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            "description": "Optional MCP workspace handle. When present, dir is relative to that workspace."
          },
          "dir": {
            "type": "string",
            "description": "Directory relative to the selected MCP workspace when workspace_id is present, otherwise relative to server --root. Omit to index the selected root itself."
          }
        }
      }
      """;

  static final String GUIDE =
      """
      {
        "type": "object",
        "properties": {
          "topic": {
            "type": "string",
            "description": "Guide topic to fetch. Omit to get the index of available topics."
          }
        }
      }
      """;
}

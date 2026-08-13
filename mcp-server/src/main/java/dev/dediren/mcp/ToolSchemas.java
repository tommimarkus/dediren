package dev.dediren.mcp;

/** The JSON input schemas the registered tools advertise, one constant per tool. */
final class ToolSchemas {
  private ToolSchemas() {}

  static final String IMPORT =
      """
      {
        "type": "object",
        "properties": {
          "source": {
            "type": "string",
            "description": "Path to external notation, relative to the workspace root. Mutually exclusive with content."
          },
          "content": {
            "type": "string",
            "description": "Inline external notation. Mutually exclusive with source; maximum UTF-8 size is 64 MiB."
          },
          "plugin": {
            "type": "string",
            "enum": ["mermaid", "dot"],
            "description": "External notation importer."
          },
          "output": {
            "type": "string",
            "enum": ["data", "svg", "image"],
            "default": "data",
            "description": "data returns the imported model envelope only; svg forces image/svg+xml; image negotiates an optional image attachment."
          },
          "accepted_image_types": {
            "type": "array",
            "uniqueItems": true,
            "items": {"type": "string", "enum": ["image/svg+xml", "image/png"]},
            "description": "Image MIME types accepted by the client when output is image. SVG has fixed priority over PNG."
          },
          "render_policy": {
            "type": "string",
            "description": "Optional workspace-confined render policy for output svg. Omit to use the bundled default SVG policy."
          }
        },
        "required": ["plugin"],
        "oneOf": [{"required": ["source"]}, {"required": ["content"]}],
        "allOf": [{"if":{"required":["accepted_image_types"]},"then":{"properties":{"output":{"const":"image"}}}}]
      }
      """;

  static final String VALIDATE =
      """
      {
        "type": "object",
        "properties": {
          "source": {
            "type": "string",
            "description": "Path to the document to validate (source model or policy JSON), relative to the workspace root."
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
          "source": {
            "type": "string",
            "description": "Path to the source JSON, relative to the workspace root."
          },
          "out": {
            "type": "string",
            "description": "Output directory for the generated artifacts, relative to the workspace root."
          },
          "output": {
            "type": "string",
            "enum": ["data", "svg", "image"],
            "default": "data",
            "description": "data returns the build result only; svg forces SVG attachments; image negotiates optional SVG or PNG attachments."
          },
          "accepted_image_types": {
            "type": "array",
            "uniqueItems": true,
            "items": {"type": "string", "enum": ["image/svg+xml", "image/png"]},
            "description": "Image MIME types accepted by the client when output is image. SVG has fixed priority over PNG."
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
            "description": "Path to a package.json declaring models, views, and exports, relative to the workspace root. Builds the whole package end to end; mutually exclusive with source/out and the per-lane policies."
          },
          "no_export": {
            "type": "boolean",
            "description": "In package mode, suppress the export lanes."
          }
        },
        "allOf": [{"if":{"required":["accepted_image_types"]},"then":{"properties":{"output":{"const":"image"}}}}]
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
          "source": {
            "type": "string",
            "description": "Path to the source model, relative to the workspace root."
          },
          "artifacts": {
            "type": "string",
            "description": "Path to the directory of built artifacts to verify, relative to the workspace root."
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
          "dir": {
            "type": "string",
            "description": "Workspace directory to index, relative to the workspace root. Omit to index the root itself."
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

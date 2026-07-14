package dev.dediren.mcp;

/** The JSON input schemas advertised for the three tools. */
final class ToolSchemas {
  private ToolSchemas() {}

  static final String VALIDATE =
      """
      {
        "type": "object",
        "properties": {
          "source": {
            "type": "string",
            "description": "Path to the source JSON, relative to the workspace root."
          },
          "profile": {
            "type": "string",
            "description": "Optional semantic profile (for example 'archimate' or 'uml'). When set, runs semantic profile validation in addition to schema validation."
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
          }
        },
        "required": ["source", "out"]
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

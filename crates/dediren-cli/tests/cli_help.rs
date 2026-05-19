mod common;

#[test]
fn top_level_help_points_to_agent_usage_guide() {
    let output = common::dediren_command()
        .arg("--help")
        .assert()
        .success()
        .get_output()
        .stdout
        .clone();
    let stdout = String::from_utf8(output).unwrap();

    assert!(
        stdout.contains("Agent authoring guide: docs/agent-usage.md"),
        "top-level help should point agents to the bundled authoring guide"
    );
    assert!(
        stdout.contains("source JSON shape"),
        "top-level help should describe when to use the agent guide"
    );
}

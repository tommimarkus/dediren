package dev.dediren.plugins.render.node.archimate;

public enum ArchimateIconKind {
  ACTOR("actor"),
  INTERFACE("interface"),
  COLLABORATION("collaboration"),
  ROLE("role"),
  SERVICE("service"),
  INTERACTION("interaction"),
  FUNCTION("function"),
  PROCESS("process"),
  EVENT("event"),
  OBJECT("object"),
  COMPONENT("component"),
  CONTRACT("contract"),
  PRODUCT("product"),
  REPRESENTATION("representation"),
  LOCATION("location"),
  GROUPING("grouping"),
  JUNCTION("junction"),
  STAKEHOLDER("stakeholder"),
  DRIVER("driver"),
  ASSESSMENT("assessment"),
  GOAL("goal"),
  OUTCOME("outcome"),
  VALUE("value"),
  MEANING("meaning"),
  CONSTRAINT("constraint"),
  REQUIREMENT("requirement"),
  PRINCIPLE("principle"),
  COURSE_OF_ACTION("course_of_action"),
  RESOURCE("resource"),
  VALUE_STREAM("value_stream"),
  CAPABILITY("capability"),
  PLATEAU("plateau"),
  WORK_PACKAGE("work_package"),
  DELIVERABLE("deliverable"),
  GAP("gap"),
  ARTIFACT("artifact"),
  SYSTEM_SOFTWARE("system_software"),
  DEVICE("device"),
  FACILITY("facility"),
  EQUIPMENT("equipment"),
  NODE("node"),
  MATERIAL("material"),
  NETWORK("network"),
  DISTRIBUTION_NETWORK("distribution_network"),
  PATH("path");

  private final String value;

  ArchimateIconKind(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }
}

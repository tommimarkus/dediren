package dev.dediren.core;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/**
 * Discovery smoke test for jqwik (Plan B P2 task 6). jqwik implements its own JUnit Platform {@code
 * TestEngine}, and this reactor runs JUnit Platform 6.1.1 while jqwik 1.9.x targets JUnit Platform
 * 1.11/1.12. This trivial property proves jqwik is discovered and executed alongside Jupiter 6
 * before any real IR property tests are built on top of it.
 */
class JqwikSmokeTest {

  @Property
  boolean anyIntEqualsItself(@ForAll int i) {
    return i == i;
  }
}

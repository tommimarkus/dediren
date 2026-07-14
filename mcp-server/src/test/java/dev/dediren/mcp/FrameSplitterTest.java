package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link FrameSplitter}'s own contract, isolated from the transport that observes it: cutting on
 * newlines, emitting a trailing unterminated frame, and -- the property F5 added -- the size bound
 * that keeps an unterminated frame from growing its buffer forever. {@link
 * DedirenMcpServerTest#serveOnStopsReadingWellBeforeAnOversizedFrameEnds} covers what an oversized
 * frame does to the server end to end; this file is only about {@link FrameSplitter}'s own state.
 */
class FrameSplitterTest {

  @Test
  void cutsFramesOnNewlinesAndKeepsTheRemainderBuffered() {
    List<String> frames = new ArrayList<>();
    FrameSplitter splitter = new FrameSplitter(frames::add);
    byte[] bytes = "one\ntwo\nthre".getBytes(StandardCharsets.UTF_8);

    splitter.accept(bytes, 0, bytes.length);

    assertThat(frames).containsExactly("one", "two");
    assertThat(splitter.flushPartial()).isTrue();
    assertThat(frames).containsExactly("one", "two", "thre");
  }

  @Test
  void isNotOversizedAtExactlyTheBound() {
    FrameSplitter splitter = new FrameSplitter(frame -> {});
    byte[] atBound = new byte[FrameSplitter.MAX_FRAME_BYTES];

    splitter.accept(atBound, 0, atBound.length);

    assertThat(splitter.exceedsMaxFrameSize()).isFalse();
  }

  @Test
  void isOversizedOnceTheStillUnterminatedFrameCrossesTheBound() {
    List<String> frames = new ArrayList<>();
    FrameSplitter splitter = new FrameSplitter(frames::add);
    byte[] overBound = new byte[FrameSplitter.MAX_FRAME_BYTES + 1];

    splitter.accept(overBound, 0, overBound.length);

    assertThat(splitter.exceedsMaxFrameSize()).isTrue();
    // The bound is a pure query, not an action: FrameSplitter itself never emits, resets, or
    // otherwise reacts on its own once crossed -- the caller decides what "oversized" means.
    assertThat(frames).isEmpty();
  }

  @Test
  void aNewlineStillEndsAFrameEvenPastTheBound() {
    List<String> frames = new ArrayList<>();
    FrameSplitter splitter = new FrameSplitter(frames::add);
    byte[] overBound = new byte[FrameSplitter.MAX_FRAME_BYTES + 1];

    splitter.accept(overBound, 0, overBound.length);
    splitter.accept('\n');

    assertThat(frames).hasSize(1);
    assertThat(splitter.exceedsMaxFrameSize())
        .as("emit() must reset the buffer, oversized or not")
        .isFalse();
  }
}

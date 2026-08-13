package dev.dediren.schemacache;

import java.io.InputStream;
import java.net.URI;

@FunctionalInterface
interface HttpTransport {
  Response fetch(URI url) throws Exception;

  record Response(int statusCode, InputStream body) {}
}

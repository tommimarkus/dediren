package dev.dediren.schemacache;

import java.net.URI;
import java.nio.file.Path;

@FunctionalInterface
public interface SchemaFetcher {
  SchemaFetchResult fetch(URI url, Path destination) throws Exception;
}

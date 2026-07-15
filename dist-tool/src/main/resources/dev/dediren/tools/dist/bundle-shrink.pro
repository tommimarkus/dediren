# Shrink-only pass over the staged launcher classpath (no optimization, no obfuscation):
# unreachable classes and members are removed, nothing is altered or renamed. Reachability
# starts from dev.dediren.** (CLI main, MCP server, engines are all entry points).
#
# Keep-rule inventory — every rule exists because removing it breaks a validated runtime path:
# - picocli.**: shrinking picocli makes CDS archive creation emit [warning][cds] lines on
#   stderr ("super class ... is excluded"), failing the bundle's quiet-stderr contract.
# - org.slf4j.**: backend wired by ServiceLoader + string-configured log levels.
# - com.fasterxml.jackson.**: Jackson annotations drive reflection on kept classes.
# - tools.jackson.databind.ext.**: optional/ext handlers (java.time etc.) wired semi-lazily.
# - io.modelcontextprotocol.spec/json: databind introspects the MCP POJOs reflectively.
# - ILayoutMetaDataProvider / SLF4JServiceProvider / McpJsonMapperSupplier /
#   JsonSchemaValidatorSupplier / TokenStreamFactory / ObjectMapper impls: ServiceLoader.
# - IDataObject impls + enum values/valueOf: ELK parses layout option values reflectively.
-dontoptimize
-dontobfuscate
-keepattributes *
-keepparameternames
-keepdirectories META-INF/services

-keep class dev.dediren.** { *; }

-keep class picocli.** { *; }
-keep class org.slf4j.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class tools.jackson.databind.ext.** { *; }
-keep class io.modelcontextprotocol.spec.** { *; }
-keep class io.modelcontextprotocol.json.** { *; }

-keep class * implements org.eclipse.elk.core.data.ILayoutMetaDataProvider { *; }
-keep class * implements org.slf4j.spi.SLF4JServiceProvider { *; }
-keep class * implements io.modelcontextprotocol.json.McpJsonMapperSupplier { *; }
-keep class * implements io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier { *; }
-keep class * implements tools.jackson.core.TokenStreamFactory { *; }
-keep class * extends tools.jackson.databind.ObjectMapper { *; }
-keep class * implements org.eclipse.elk.core.util.IDataObject { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Scoped, not `-dontwarn **`: these namespaces reference OSGi, Eclipse runtime, micrometer,
# blockhound, sun.misc and other optional platforms the plain-Java bundle intentionally
# omits — those references are dead code here. Anything OUTSIDE these namespaces that fails
# to resolve still fails the dist build loudly, so a typo'd keep rule or a genuinely missing
# class cannot be silenced by accident.
-dontwarn org.eclipse.**
-dontwarn reactor.**
-dontwarn com.google.**
-dontwarn io.modelcontextprotocol.**
# networknt's optional ECMA-262 regex backends (joni, GraalVM polyglot) are not bundled.
-dontwarn com.networknt.schema.regex.**
# The YAML stack is deliberately excluded from every runtime classpath (see core/pom.xml);
# networknt's YAML mapper holder is the one intentionally dangling reference left behind.
-dontwarn com.networknt.schema.serialization.YamlMapperFactory*
# MethodHandle.invokeExact is signature-polymorphic; ProGuard's library-member resolution
# flags those call sites as missing members. False positive, not a real unresolved reference.
-dontwarn tools.jackson.databind.**
-dontnote **

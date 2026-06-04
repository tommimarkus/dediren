package dev.dediren.contracts.layout;

public record LayoutRoutingPreferences(
        LayoutRoutingStyle style,
        LayoutRoutingProfile profile,
        LayoutEndpointMerging endpointMerging) {
}

package dev.dediren.core;

/**
 * The Dediren product root (schemas/, fixtures/) could not be resolved from the configured override
 * or the working-directory walk-up. Subclasses IllegalStateException so pre-existing broad catches
 * keep working; cli converts it to a DEDIREN_PRODUCT_ROOT_UNRESOLVED envelope.
 */
public final class ProductRootException extends IllegalStateException {
  public ProductRootException(String message) {
    super(message);
  }
}

package org.itech.ahb.profile;

/** A requested profile, revision, or draft does not exist in the catalog. */
public final class ProfileNotFoundException extends ProfileCatalogException {

  public ProfileNotFoundException(String message) {
    super(message);
  }
}

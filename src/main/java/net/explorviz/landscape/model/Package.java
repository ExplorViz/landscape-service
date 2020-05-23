package net.explorviz.landscape.model;

import java.util.Collection;

/**
 * Abstraction of a package/namespace.
 */
public class Package {

  private final String name;
  private final Collection<Package> subPackages;
  private final Collection<Clazz> classes;

  public Package(String name, Collection<Package> subPackages,
                 Collection<Clazz> classes) {
    this.name = name;
    this.subPackages = subPackages;
    this.classes = classes;
  }

  public String getName() {
    return name;
  }

  public Collection<Package> getSubPackages() {
    return subPackages;
  }

  public Collection<Clazz> getClasses() {
    return classes;
  }
}
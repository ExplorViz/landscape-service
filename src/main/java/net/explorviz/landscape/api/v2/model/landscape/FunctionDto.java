package net.explorviz.landscape.api.v2.model.landscape;

import net.explorviz.landscape.ogm.Function;

/**
 * Functions are not directly visualized in the software landscape; they can be viewed e.g. via
 * popups, and they are central to the visualization of communication between classes.
 *
 * @param name Name of the function / method
 * @param methodHash This is a value we used to calculate to match spans with the functions they
 *     belong to, since multiple spans can refer to the same function. In the landscape-service, we
 *     no longer need a hash calculation as we can simply return the ID of the function node. Note
 *     that the naming "method" is for compatibility with the v2-API, which preferred the term
 *     "method" over "function"
 */
public record FunctionDto(String name, String methodHash) {
  public FunctionDto(final Function ogmFunc) {
    this(ogmFunc.getName(), ogmFunc.getId().toString());
  }
}

package crux.ast.types;

/**
 * Types for Integers values. This should implement the equivalent methods along with add, sub, mul,
 * div, and compare. The method equivalent will check if the param is an instance of IntType.
 */
public final class IntType extends Type implements java.io.Serializable {
  static final long serialVersionUID = 12022L;

  @Override
  public String toString() {
    return "int";
  }

  @Override
  Type add(Type that) {
    if (this.equivalent(that)) {
      return new IntType();
    }
    return super.add(that);
  }

  @Override
  Type sub(Type that) {
    if (this.equivalent(that)) {
      return new IntType();
    }
    return super.sub(that);
  }

  @Override
  Type mul(Type that) {
    if (this.equivalent(that)) {
      return new IntType();
    }
    return super.mul(that);
  }

  @Override
  Type div(Type that) {
    if (this.equivalent(that)) {
      return new IntType();
    }
    return super.div(that);
  }

  @Override
  Type compare(Type that) {
    // We can only compare two integers
    if (this.equivalent(that)) {
      return new BoolType();
    }
    return super.compare(that);
  }

  @Override
  Type assign(Type source) {
    if (this.equivalent(source)) {
      return new IntType();
    }
    return super.assign(source);
  }

  @Override
  public boolean equivalent(Type that) {
    // that is an instance of IntType
    return that.getClass() == IntType.class;
  }
}

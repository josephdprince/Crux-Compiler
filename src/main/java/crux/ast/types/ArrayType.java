package crux.ast.types;

/**
 * The variable base is the type of the array element. This could be int or
 * bool. The extent variable is number of elements in the array.
 *
 */
public final class ArrayType extends Type implements java.io.Serializable {
  static final long serialVersionUID = 12022L;
  private final Type base;
  private final long extent;

  public ArrayType(long extent, Type base) {
    this.extent = extent;
    this.base = base;
  }

  public Type getBase() { return base; }

  public long getExtent() { return extent; }

  @Override
  public String toString() {
    return String.format("array[%d,%s]", extent, base);
  }

  @Override
  Type index(Type that) {
    if (that.toString().equals("int")) {
      return this.getCorrectRet();
    }
    return super.index(that);
  }

  @Override
  Type assign(Type source) {
    if (this.equivalent(source)) {
      return this.getCorrectRet();
    }
    return super.assign(source);
  }

  Type getCorrectRet() {
    if (this.getBase().toString().equals("int")) {
      return new IntType();
    }
    return new BoolType();
  }

  @Override
  public boolean equivalent(Type that) {
    if (that.getClass() == ArrayType.class) {
      return this.getBase().toString().equals(((ArrayType) that).getBase().toString());
    }
    return this.getBase().toString().equals(that.toString());
  }
}

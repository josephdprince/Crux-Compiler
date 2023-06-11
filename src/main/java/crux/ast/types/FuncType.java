package crux.ast.types;

/**
 * The field args is a TypeList with a type for each param. The type ret is the type 
 * of the function return. The function return could be int, bool, or void. This 
 * class should implement the call method.
 */
public final class FuncType extends Type implements java.io.Serializable {
  static final long serialVersionUID = 12022L;

  private TypeList args;
  private Type ret;

  public FuncType(TypeList args, Type returnType) {
    this.args = args;
    this.ret = returnType;
  }

  public Type getRet() { return ret; }

  public TypeList getArgs() { return args; }

  @Override
  public String toString() {
    return "func(" + args + "):" + ret;
  }

  @Override
  Type call(Type args) {
    if (this.getArgs().equivalent(args)) {
      return this.getRet(); // result is return type
    }
    return super.call(args);
  }

  // Checks for three comparisons:
  //    1. that is an instance of FuncType class AND
  //    2. return type of that and this are the same AND 
  //    3. that's args types are equivalent to this
  @Override
  public boolean equivalent(Type that) {
    return false;
  }
}

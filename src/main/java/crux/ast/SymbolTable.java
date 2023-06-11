package crux.ast;

import crux.ast.Position;
import crux.ast.types.*;


import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Symbol table will map each symbol from Crux source code to its declaration or appearance in the
 * source. The symbol table is made up of scopes, Each scope is a map which maps an identifier to
 * it's symbol. Scopes are inserted to the table starting from the first scope (Global Scope). The
 * Global scope is the first scope in each Crux program and it contains all the built in functions
 * and names. The symbol table is an ArrayList of scopes.
 */
public final class SymbolTable {

  /**
   * Symbol is used to record the name and type of names in the code. Names include function names,
   * global variables, global arrays, and local variables.
   */
  static public final class Symbol implements java.io.Serializable {
    static final long serialVersionUID = 12022L;
    private final String name;
    private final Type type;
    private final String error;

    /**
     *
     * @param name String
     * @param type the Type
     */
    private Symbol(String name, Type type) {
      this.name = name;
      this.type = type;
      this.error = null;
    }

    private Symbol(String name, String error) {
      this.name = name;
      this.type = null;
      this.error = error;
    }

    /**
     *
     * @return String the name
     */
    public String getName() {
      return name;
    }

    /**
     *
     * @return the type
     */
    public Type getType() {
      return type;
    }

    @Override
    public String toString() {
      if (error != null) {
        return String.format("Symbol(%s:%s)", name, error);
      }
      return String.format("Symbol(%s:%s)", name, type);
    }

    public String toString(boolean includeType) {
      if (error != null) {
        return toString();
      }
      return includeType ? toString() : String.format("Symbol(%s)", name);
    }
  }

  private final PrintStream err;

  // Global scope at index 0 and becomes more local further into the array
  private final ArrayList<Map<String, Symbol>> symbolScopes = new ArrayList<>();

  private boolean encounteredError = false;

  SymbolTable(PrintStream err) {
    this.err = err;
    symbolScopes.add(new HashMap<String, Symbol>());

    ArrayList<Type> pBoolParams = new ArrayList<Type>();
    pBoolParams.add(new BoolType());
    ArrayList<Type> pIntParams = new ArrayList<Type>();
    pIntParams.add(new IntType());
    ArrayList<Type> pCharParams = new ArrayList<Type>();
    pCharParams.add(new IntType());
    

    Symbol readInt = new Symbol("readInt", new FuncType(new TypeList(new ArrayList<Type>()), new IntType()));
    Symbol readChar = new Symbol("readChar", new FuncType(new TypeList(new ArrayList<Type>()), new IntType()));
    Symbol printBool = new Symbol("printBool", new FuncType(new TypeList(pBoolParams), new VoidType()));
    Symbol printInt = new Symbol("printInt", new FuncType(new TypeList(pIntParams), new VoidType()));
    Symbol printChar = new Symbol("printChar", new FuncType(new TypeList(pCharParams), new VoidType()));
    Symbol println = new Symbol("println", new FuncType(new TypeList(new ArrayList<Type>()), new VoidType()));

    symbolScopes.get(0).put("readInt", readInt);
    symbolScopes.get(0).put("readChar", readChar);
    symbolScopes.get(0).put("printBool", printBool);
    symbolScopes.get(0).put("printInt", printInt);
    symbolScopes.get(0).put("printChar", printChar);
    symbolScopes.get(0).put("println", println);
  }

  boolean hasEncounteredError() {
    return encounteredError;
  }

  /**
   * Called to tell symbol table we entered a new scope.
   */

  void enter() {
    symbolScopes.add(new HashMap<String, Symbol>());
  }

  /**
   * Called to tell symbol table we are exiting a scope.
   */

  void exit() {
    if (!symbolScopes.isEmpty()) {
      symbolScopes.remove(symbolScopes.size() - 1);
    }
    
  }

  /**
   * Insert a symbol to the table at the most recent scope. if the name already exists in the
   * current scope that's a declareation error.
   */
  Symbol add(Position pos, String name, Type type) {
    // First check if name already exists in scope
    if (symbolScopes.get(symbolScopes.size() - 1).get(name) != null) {
      err.printf("DeclarationError%s: %s already exists", pos, name);
      encounteredError = true;
      return null;
    }
    Symbol sym = new Symbol(name, type);
    symbolScopes.get(symbolScopes.size() - 1).put(name, sym);
    return sym;
  }

  /**
   * lookup a name in the SymbolTable, if the name not found in the table it should encounter an
   * error and return a symbol with ResolveSymbolError error. if the symbol is found then return it.
   */
  Symbol lookup(Position pos, String name) {
    var symbol = find(name);
    if (symbol == null) {
      err.printf("ResolveSymbolError%s[Could not find %s.]%n", pos, name);
      encounteredError = true;
      return new Symbol(name, "ResolveSymbolError");
    } else {
      return symbol;
    }
  }

  /**
   * Try to find a symbol in the table starting form the most recent scope.
   */
  private Symbol find(String name) {
    for (int i = symbolScopes.size() - 1; i >= 0; --i) {
      var symbol = symbolScopes.get(i).get(name);
      if (symbol != null) {
        return symbol;
      }
    }

    return null;
  }
}

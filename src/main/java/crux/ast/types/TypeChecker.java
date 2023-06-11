package crux.ast.types;

import crux.ast.SymbolTable.Symbol;
import crux.ast.*;
import crux.ast.traversal.NullNodeVisitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.lang.model.util.ElementScanner6;

/**
 * This class will associate types with the AST nodes from Stage 2
 */
public final class TypeChecker {
  private final ArrayList<String> errors = new ArrayList<>();

  Symbol currentFunctionSymbol = null;
  boolean lastStatementReturns = false;
  int inLoop = 0;

  public ArrayList<String> getErrors() {
    return errors;
  }

  public void check(DeclarationList ast) {
    var inferenceVisitor = new TypeInferenceVisitor();
    inferenceVisitor.visit(ast);
  }

  /**
   * Helper function, should be used to add error into the errors array
   */
  private void addTypeError(Node n, String message) {
    errors.add(String.format("TypeError%s[%s]", n.getPosition(), message));
  }

  /**
   * Helper function, should be used to record Types if the Type is an ErrorType then it will call
   * addTypeError
   */
  private void setNodeType(Node n, Type ty) {
    ((BaseNode) n).setType(ty);
    if (ty.getClass() == ErrorType.class) {
      var error = (ErrorType) ty;
      addTypeError(n, error.getMessage());
    }
  }

  /**
   * Helper to retrieve Type from the map
   */
  public Type getType(Node n) {
    return ((BaseNode) n).getType();
  }


  /**
   * This calls will visit each AST node and try to resolve it's type with the help of the
   * symbolTable.
   */
  private final class TypeInferenceVisitor extends NullNodeVisitor<Void> {
    @Override
    public Void visit(VarAccess vaccess) {
      setNodeType(vaccess, vaccess.getSymbol().getType());
      return null;
    }

    @Override
    public Void visit(ArrayDeclaration arrayDeclaration) {
      setNodeType(arrayDeclaration, ((ArrayType) arrayDeclaration.getSymbol().getType()).getBase());
      return null;
    }

    @Override
    public Void visit(Assignment assignment) {
      // getChildren returns a list of lhs and rhs (so always a list of size 2).
      // Instead, I will access the data using getLocation (lhs) and getValue (rhs)
      Expression lhs = assignment.getLocation();
      Expression rhs = assignment.getValue();
      lhs.accept(this);
      rhs.accept(this);

      // Now perform the type check (must cast to BaseNode using getType)
      Type status = getType(lhs).assign(getType(rhs));
      
      // Set this node with the correct type
      setNodeType(assignment, status);
      
      return null;
    }

    @Override
    public Void visit(Break brk) {
      if (inLoop <= 0) {
        addTypeError(brk, "Invalid use of break");
      }
      return null;
    }

    @Override
    public Void visit(Call call) {
      Symbol func = call.getCallee();
      ArrayList<Type> paramTypes = new ArrayList<Type>();

      // Get type of each param
      for (Node param : call.getChildren()) {
        param.accept(this);
        paramTypes.add(getType(param));
      }
      TypeList l = new TypeList(paramTypes);      

      // Check if params match function
      setNodeType(call, func.getType().call(l));      

      return null;
    }

    @Override
    public Void visit(DeclarationList declarationList) {
      for (Node n : declarationList.getChildren()) {
        n.accept(this);
      }
      return null;
    }

    @Override
    public Void visit(FunctionDefinition functionDefinition) {
      currentFunctionSymbol = functionDefinition.getSymbol();
      String funcName = currentFunctionSymbol.getName();
      String funcRetType = ((FuncType) currentFunctionSymbol.getType()).getRet().toString();

      // Sets the function node type to return type 
      setNodeType(functionDefinition, ((FuncType) currentFunctionSymbol.getType()).getRet());

      // Handle return
      if (!funcRetType.equals("void")) {
        lastStatementReturns = true;
      }
      else {
        lastStatementReturns = false;
      }

      // Ensure that main function has a void return type and does not have any params
      if (funcName.equals("main")) {
        if (!funcRetType.equals("void")) {
          addTypeError(functionDefinition, "Main function must have type void");
        }
        if (!functionDefinition.getParameters().isEmpty()) {
          addTypeError(functionDefinition, "Main function cannot have arguments");
        }
      }

      // Handle parameters
      int paramNum = 1;
      for (Symbol param : functionDefinition.getParameters()) {
        String typeName = param.getType().toString();
        if (!typeName.equals("int") && !typeName.equals("bool")) {
          addTypeError(functionDefinition, "Parameter " + paramNum + " in " + funcName + " has invalid type");
        }
        paramNum += 1;
      }

      // Handle function body
      this.visit(functionDefinition.getStatements());

      lastStatementReturns = false;
      
      return null;
    }

    @Override
    /**
     * A visit to the ifElseBranch will check for the condition type to be bool, and
     * recurse to visit on each statements in the then-block and/or the else-block
     */
    public Void visit(IfElseBranch ifElseBranch) {
      Expression ifCondition = ifElseBranch.getCondition();
      StatementList ifThenBlock = ifElseBranch.getThenBlock();
      StatementList ifElseBlock = ifElseBranch.getElseBlock();

      ifCondition.accept(this);

      // A condition expression can only be a bool
      if (getType(ifCondition).getClass() != BoolType.class) {
        addTypeError(ifCondition, "IfElse condition expression must evaluate to bool type"); 
      }

      // Handle if-then body
      ifThenBlock.accept(this);

      // Handle else body, could be null
      if (ifElseBlock != null) {
        ifElseBlock.accept(this);
      }

      return null;
    }

    @Override 
    /**
     * A visit to an ArrayAccess node will store the type of an array's element. The 
     * element is accessed by indexing the array (e.g. a[5]).Assuming the base type of 
     * the array will be IntType or BoolType, we don't check for invalid base types here.
     */
    public Void visit(ArrayAccess access) {
      Type baseType = access.getBase().getType();
      Node indexNode = access.getIndex();

      // To infer the element type, we need to know the index type too
      indexNode.accept(this); 

      // Now we can type check
      Type elementType = baseType.index(getType(indexNode)); 
      
      setNodeType(access, elementType);
      return null;
    }

    @Override 
    public Void visit(LiteralBool literalBool) {
      setNodeType(literalBool, new BoolType());
      return null;
    }

    @Override 
    public Void visit(LiteralInt literalInt) {
      setNodeType(literalInt, new IntType());
      return null;
    }

    @Override 
    /**
     * A visit to a For will visit the initialization assignement, make sure
     * the condition expression is of type bool, visit the increment assignemnt,
     * and visit the statements in the for-loop body
     */
    public Void visit(For forloop) {
      inLoop++;

      Assignment forInit = forloop.getInit();
      Expression forCond = forloop.getCond();
      Assignment forincr = forloop.getIncrement();
      StatementList forBody = forloop.getBody();

      forInit.accept(this);
      forCond.accept(this);
      forincr.accept(this);

      if (getType(forCond).getClass() != BoolType.class) {
        addTypeError(forincr, "For loop condition expression must evaluate to bool type");
      }

      forBody.accept(this);
      inLoop--;

      return null;
    }

    @Override
    /**
     * Visiting an OpExpr will visit the left hand side and right hand side of an
     * operation to infer their types; then it will type check both sides of the operation, 
     * and associate the node with the correct type for the evaluation of the expression
     */
    public Void visit(OpExpr op) {
      OpExpr.Operation operator = op.getOp();
      Expression lhs = op.getLeft();
      Expression rhs = op.getRight();

      lhs.accept(this);
      
      // rhs is null if the operator is LOGIC_NOT
      if (!operator.toString().equals("!")) {
        rhs.accept(this);
      }

      // Type check depends on the operator used
      Type t = null;

      if ((getType(lhs).getClass() == IntType.class) && 
          (operator.toString().equals(">=") || operator.toString().equals("<=") ||
          operator.toString().equals(">")  || operator.toString().equals("<"))) {
        t = getType(lhs).compare(getType(rhs));
      }
      else if (operator.toString().equals("==") || operator.toString().equals("!=")) {
        t = getType(lhs).compare(getType(rhs));
      }
      else if (operator.toString().equals("+")) {
        t = getType(lhs).add(getType(rhs));
      }
      else if (operator.toString().equals("-")) {
        t = getType(lhs).sub(getType(rhs));
      }
      else if (operator.toString().equals("*")) {
        t = getType(lhs).mul(getType(rhs));
      }
      else if (operator.toString().equals("/")) {
        t = getType(lhs).div(getType(rhs));
      }
      else if (operator.toString().equals("&&")) {
        t = getType(lhs).and(getType(rhs));
      }
      else if (operator.toString().equals("||")) {
        t = getType(lhs).or(getType(rhs));
      }
      else if (operator.toString().equals("!")) {
        t = getType(lhs).not();
      }
      else {
        addTypeError(op, "Unkown operator");
      }

      // update node type mapping
      if (t != null) {
        setNodeType(op, t);
      }

      return null;
    }

    @Override 
    /**
     * A visit to Return will ensure that the return expression type matches with the
     * function return type. Assumes currentFunctionSymbol contains only valid function
     * types (int, bool, void), indirectly checking for invalid return types.
     * 
     * Call and FunctionDefinition sets lastStatementReturns to false when a return
     * type is void. So, if there is a non-void return statement then check return type 
     * to match with the function return type
     */
    public Void visit(Return ret) {
      ret.getValue().accept(this);
      Type retType = getType(ret.getValue());

      // If there is return statement but we are not expecting one, then error
      if (!lastStatementReturns) {
        addTypeError(ret, "Invalid use of return keyword");
      } 
      else if (!retType.toString().equals(((FuncType) currentFunctionSymbol.getType()).getRet().toString())) {
        addTypeError(ret, "Return expression type does not match function return type");
      }
      else {
        setNodeType(ret, retType);
      }

      return null;
    }

    @Override 
    public Void visit(StatementList statementList) {
      for (Node n : statementList.getChildren()) {
        n.accept(this);
      }
      return null;
    }

    @Override
    public Void visit(VariableDeclaration variableDeclaration) {
      setNodeType(variableDeclaration, variableDeclaration.getSymbol().getType());
      return null;
    }
  }
}

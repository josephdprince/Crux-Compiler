package crux.ir;

import crux.ast.SymbolTable.Symbol;
import crux.ast.*;
import crux.ast.OpExpr.Operation;
import crux.ast.traversal.NodeVisitor;
import crux.ast.types.*;
import crux.ir.insts.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// This represents a block in the CFG
class InstPair {
  private Instruction start;
  private Instruction end;
  private LocalVar value;

  InstPair(Instruction start, Instruction end) {
    this.start = start;
    this.end = end;
    this.value = null;
  }

  InstPair(Instruction start, Instruction end, LocalVar value) {
    this.start = start;
    this.end = end;
    this.value = value;
  }

  Instruction getStart() {
    return start;
  }

  Instruction getEnd() {
    return end;
  }

  LocalVar getValue() {
    return value;
  }
}

/**
 * Convert AST to IR and build the CFG
 */
public final class ASTLower implements NodeVisitor<InstPair> {
  private Program mCurrentProgram = null;
  private Function mCurrentFunction = null;
  Instruction currLoopExit = null; // used by break instr in nested loops 

  private Map<Symbol, LocalVar> mCurrentLocalVarMap = null;

  /**
   * A constructor to initialize member variables
   */
  public ASTLower() {
    mCurrentLocalVarMap = new HashMap<Symbol, LocalVar>();
  }

  public Program lower(DeclarationList ast) {
    visit(ast);
    return mCurrentProgram;
  }

  // This declList is the whole program (either a vardecl or a function).
  // Each one will be stored in the program (handled outside of the scope of this func).
  @Override
  public InstPair visit(DeclarationList declarationList) {
    mCurrentProgram = new Program();
    for (Node decl : declarationList.getChildren()) {
      decl.accept(this);
    }
    
    return null;
  }

  /**
   * This visitor should create a Function instance for the functionDefinition node, add parameters
   * to the localVarMap, add the function to the program, and init the function start Instruction.
   */
  @Override
  public InstPair visit(FunctionDefinition functionDefinition) { 
    // Get function info to create function type
    String fName = functionDefinition.getSymbol().getName();
    FuncType fType = ((FuncType) functionDefinition.getSymbol().getType());
    
    Function func = new Function(fName, fType);
    mCurrentFunction = func;

    // Start handling parameters
    ArrayList<LocalVar> arguments = new ArrayList<LocalVar>();
    for (Symbol param : functionDefinition.getParameters()) {
      LocalVar pVar = func.getTempVar(param.getType());
      mCurrentLocalVarMap.put(param, pVar);
      arguments.add(pVar);
    }
    func.setArguments(arguments);

    // Add function to program
    mCurrentProgram.addFunction(func);

    // Visit body and get instrcutions
    InstPair insts = visit(functionDefinition.getStatements());
    func.setStart(insts.getStart());

    // Clear data
    mCurrentLocalVarMap.clear();
    mCurrentFunction = null;
    
    return null;
  }

  @Override
  public InstPair visit(StatementList statementList) {
    Instruction start = new NopInst();
    Instruction prev = start;

    // Visit each statement and connect them
    for (Node stmt : statementList.getChildren()) {
      InstPair curr = stmt.accept(this);

      prev.setNext(0, curr.getStart());
      prev = curr.getEnd();
    }

    return new InstPair(start, prev);
  }

  /**
   * Declarations, could be either local or Global
   */
  @Override
  public InstPair visit(VariableDeclaration variableDeclaration) {
    // handle global variable
    if (mCurrentFunction == null) {
      mCurrentProgram.addGlobalVar(new GlobalDecl(variableDeclaration.getSymbol(),
                                                  IntegerConstant.get(mCurrentProgram, 1)));
      return null;
    }

    // Local var
    LocalVar pVar = mCurrentFunction.getTempVar(variableDeclaration.getSymbol().getType());
    mCurrentLocalVarMap.put(variableDeclaration.getSymbol(), pVar);
    Instruction nop = new NopInst();
    return new InstPair(nop, nop);
  }

  /**
   * Create a declaration for array and connected it to the CFG
   */
  @Override
  public InstPair visit(ArrayDeclaration arrayDeclaration) {
    // Array decl must be global
    mCurrentProgram.addGlobalVar(new GlobalDecl(arrayDeclaration.getSymbol(),
                                                IntegerConstant.get(mCurrentProgram, 
                                                ((ArrayType) arrayDeclaration.getSymbol().getType()).getExtent())));
    return null;
  }

  /**
   * LookUp the name in the map(s). For globals, we should do a load to get the value to load into a
   * LocalVar.
   */
  @Override
  public InstPair visit(VarAccess name) {
    LocalVar v = mCurrentLocalVarMap.get(name.getSymbol());
    
    // If varaccess on global variable
    if (v == null) {
      // AddressAt instruction
      AddressVar addr = mCurrentFunction.getTempAddressVar(name.getSymbol().getType());
      AddressAt addrAtInst = new AddressAt(addr, name.getSymbol());
      
      // Load instruction
      LocalVar gVar = mCurrentFunction.getTempVar(name.getSymbol().getType());
      LoadInst loadInst = new LoadInst(gVar, addr);

      // Connect the two instructions
      addrAtInst.setNext(0, loadInst);

      return new InstPair(addrAtInst, loadInst, gVar);
    }
    
    // Local var
    Instruction nop = new NopInst();
    return new InstPair(nop, nop, v);
  }

  /**
   * If the location is a VarAccess to a LocalVar, copy the value to it. If the location is a
   * VarAccess to a global, store the value. If the location is ArrayAccess, store the value.
   */
  @Override
  public InstPair visit(Assignment assignment) {
    Expression lhs = assignment.getLocation();
    Expression rhs = assignment.getValue();
    boolean arrayAccess = false;

    // First check if VarAccess or ArrayAccess
    Symbol varSym;
    if (lhs.getClass() == VarAccess.class) {
      varSym = ((VarAccess) lhs).getSymbol();
    }
    else {
      varSym = ((ArrayAccess) lhs).getBase();
      arrayAccess = true;
    }

    LocalVar lVar = mCurrentLocalVarMap.get(varSym);

    // Global var or array access
    if (lVar == null) {
      AddressVar addr = mCurrentFunction.getTempAddressVar(varSym.getType());

      if (arrayAccess) {
        InstPair indexInst = visit(((ArrayAccess) lhs).getIndex());
        AddressAt arrayAddrAtInst = new AddressAt(addr, varSym, indexInst.getValue());
        InstPair rhsInsts = visit(rhs);
        indexInst.getEnd().setNext(0, rhsInsts.getStart());
        rhsInsts.getEnd().setNext(0, arrayAddrAtInst);
        StoreInst store = new StoreInst(rhsInsts.getValue(), addr);
        arrayAddrAtInst.setNext(0, store);
        return new InstPair(indexInst.getStart(), store);
      }

      InstPair rhsInsts = visit(rhs);
      AddressAt addrAtInst = new AddressAt(addr, varSym);
      StoreInst store = new StoreInst(rhsInsts.getValue(), addr);
      rhsInsts.getEnd().setNext(0, addrAtInst);
      addrAtInst.setNext(0, store);

      return new InstPair(rhsInsts.getStart(), store);
    }
    
    // Local varAccess
    InstPair lhsInsts = visit(lhs);
    InstPair rhsInsts = visit(rhs);
    rhsInsts.getEnd().setNext(0, lhsInsts.getStart());
    Instruction copy = new CopyInst(lhsInsts.getValue(), rhsInsts.getValue());
    lhsInsts.getEnd().setNext(0, copy);
    return new InstPair(rhsInsts.getStart(), copy);
  }

  /**
   * Lower a Call.
   */
  @Override
  public InstPair visit(Call call) {
    Instruction startInst = new NopInst();
    Instruction prev = startInst;

    ArrayList<LocalVar> params = new ArrayList<LocalVar>();
    for (Expression arg : call.getArguments()) {
      // Visit argument
      InstPair argInsts = visit(arg);

      prev.setNext(0, argInsts.getStart());
      prev = argInsts.getEnd();

      // Add value to param list
      params.add(argInsts.getValue());
    }

    // Get callee symbol
    Symbol callee = call.getCallee();

    // Get return variable if not void
    Type t = ((FuncType) callee.getType()).getRet();
    if (!t.toString().equals("void")) {
      LocalVar destVar = mCurrentFunction.getTempVar(t);
      CallInst cInst = new CallInst(destVar, callee, params);
      prev.setNext(0, cInst);
      return new InstPair(startInst, cInst, destVar);
    }
    
    CallInst cInst = new CallInst(callee, params);
    prev.setNext(0, cInst);
    return new InstPair(startInst, cInst);
  }

  /**
   * Handle operations like arithmetics and comparisons. Also handle logical operations (and,
   * or, not).
   */
  @Override
  public InstPair visit(OpExpr operation) {
    Operation op = operation.getOp();
    Expression lhs = operation.getLeft();
    Expression rhs = operation.getRight();
    
    InstPair lInsts = visit(lhs);

    // Comparison operator
    CompareInst.Predicate p = null;
    if (op.toString().equals(">=")) {
      p = CompareInst.Predicate.GE;
    }
    else if (op.toString().equals("<=")) {
      p = CompareInst.Predicate.LE;
    }
    else if (op.toString().equals("!=")) {
      p = CompareInst.Predicate.NE;
    }
    else if (op.toString().equals("==")) {
      p = CompareInst.Predicate.EQ;
    }
    else if (op.toString().equals(">")) {
      p = CompareInst.Predicate.GT;
    }
    else if (op.toString().equals("<")) {
      p = CompareInst.Predicate.LT;
    }
    if (p != null) {
      LocalVar destVar = mCurrentFunction.getTempVar(new BoolType());
      InstPair rInsts = visit(rhs);
      CompareInst compInst = new CompareInst(destVar, p, lInsts.getValue(), rInsts.getValue());

      lInsts.getEnd().setNext(0, rInsts.getStart());
      rInsts.getEnd().setNext(0, compInst);

      return new InstPair(lInsts.getStart(), compInst, destVar);
    }

    // Binary operator
    BinaryOperator.Op o = null;
    if (op.toString().equals("+")) {
      o = BinaryOperator.Op.Add;  
    }
    else if (op.toString().equals("-")) {
      o = BinaryOperator.Op.Sub;  
    }
    else if (op.toString().equals("*")) {
      o = BinaryOperator.Op.Mul;  
    }
    else if (op.toString().equals("/")) {
      o = BinaryOperator.Op.Div;  
    }
    if (o != null) {
      // Get rInsts and connect with lInsts
      InstPair rInsts = visit(rhs);
      lInsts.getEnd().setNext(0, rInsts.getStart());
      
      LocalVar destVar = mCurrentFunction.getTempVar(new IntType());
      BinaryOperator binInst = new BinaryOperator(o, destVar, lInsts.getValue(), rInsts.getValue());
      rInsts.getEnd().setNext(0, binInst);
      return new InstPair(lInsts.getStart(), binInst, destVar);
    }

    // Logical operator
    LocalVar lhsVal = lInsts.getValue();
    Instruction jumpInst = new JumpInst(lhsVal);
    Instruction exitInst = new NopInst();
    LocalVar destVar = mCurrentFunction.getTempVar(new BoolType());

    if (op.toString().equals("!")) {
      UnaryNotInst notInst = new UnaryNotInst(destVar, lhsVal);
      lInsts.getEnd().setNext(0, notInst);
      return new InstPair(lInsts.getStart(), notInst, destVar);
    }
    
    if (op.toString().equals("||")) {
      lInsts.getEnd().setNext(0, jumpInst);

      // handle true - short circuit, no need to evaluate rhs
      Instruction shortCircuitInst = new CopyInst(destVar, lhsVal);
      jumpInst.setNext(1, shortCircuitInst); 
      shortCircuitInst.setNext(0, exitInst);

      // handle false
      InstPair rInsts = visit(rhs);
      jumpInst.setNext(0, rInsts.getStart());
      Instruction notShortCircuitInst = new CopyInst(destVar, rInsts.getValue());
      rInsts.getEnd().setNext(0, notShortCircuitInst);
      notShortCircuitInst.setNext(0, exitInst);
    } 
    else if (op.toString().equals("&&")) {
      lInsts.getEnd().setNext(0, jumpInst);

      // handle true
      InstPair rInsts = visit(rhs);
      jumpInst.setNext(1, rInsts.getStart());
      Instruction notShortCircuitInst = new CopyInst(destVar, rInsts.getValue());
      rInsts.getEnd().setNext(0, notShortCircuitInst);
      notShortCircuitInst.setNext(0, exitInst);

      // handle false - short circuit, no need to evaluate rhs
      Instruction shortCircuitInst = new CopyInst(destVar, lhsVal);
      jumpInst.setNext(0, shortCircuitInst);
      shortCircuitInst.setNext(0, exitInst);
    }
    
    return new InstPair(lInsts.getStart(), exitInst, destVar);
  }

  // Can either be a literalbool, literalint, or opexpr. Should just be able to call accept
  // and that will take care of everything  
  private InstPair visit(Expression expression) {
    return expression.accept(this);
  }

  /**
   * It should compute the address into the array, do the load, and return the value in a LocalVar.
   */
  @Override
  public InstPair visit(ArrayAccess access) {
    InstPair indexInsts = visit(access.getIndex());

    Symbol sym = access.getBase();
    Type t = ((ArrayType) sym.getType()).getBase();
    AddressVar addr =  mCurrentFunction.getTempAddressVar(t);
    AddressAt addrAtInst = new AddressAt(addr, sym, indexInsts.getValue());
    indexInsts.getEnd().setNext(0, addrAtInst);

    LocalVar lVar = mCurrentFunction.getTempVar(t);
    LoadInst loadInst = new LoadInst(lVar, addr);

    addrAtInst.setNext(0, loadInst);

    return new InstPair(indexInsts.getStart(), loadInst, lVar);
  }

  /**
   * Copy the literal into a tempVar
   */
  @Override
  public InstPair visit(LiteralBool literalBool) {
    LocalVar tempVar = mCurrentFunction.getTempVar(new BoolType());
    CopyInst copyInst = new CopyInst(tempVar, BooleanConstant.get(mCurrentProgram, literalBool.getValue()));
    
    return new InstPair(copyInst, copyInst, tempVar);
  }

  /**
   * Copy the literal into a tempVar
   */
  @Override
  public InstPair visit(LiteralInt literalInt) {
    LocalVar tempVar = mCurrentFunction.getTempVar(new IntType());
    Instruction copyInst = new CopyInst(tempVar, IntegerConstant.get(mCurrentProgram, literalInt.getValue()));

    return new InstPair(copyInst, copyInst, tempVar);
  }

  /**
   * Lower a Return.
   */
  @Override
  public InstPair visit(Return ret) {
    InstPair rhsInstr = visit(ret.getValue());
    Instruction returnInst = new ReturnInst(rhsInstr.getValue());

    rhsInstr.getEnd().setNext(0, returnInst);

    return new InstPair(rhsInstr.getStart(), returnInst, rhsInstr.getValue());
  }

  /**
   * Break Node
   */
  @Override
  public InstPair visit(Break brk) {
    return new InstPair(currLoopExit, new NopInst());
  }

  /**
   * Implement If Then Else statements.
   */
  @Override
  public InstPair visit(IfElseBranch ifElseBranch) {
    Expression condition = ifElseBranch.getCondition();
    StatementList thenBlock = ifElseBranch.getThenBlock();
    StatementList elseBlock = ifElseBranch.getElseBlock();

    InstPair predicate = visit(condition);

    Instruction jumpInstr = new JumpInst(predicate.getValue());
    Instruction exitInstr = new NopInst();

    predicate.getEnd().setNext(0, jumpInstr);
    // handle when predicate is true
    InstPair thenInstBody = visit(thenBlock);

    jumpInstr.setNext(1, thenInstBody.getStart());
    thenInstBody.getEnd().setNext(0, exitInstr);

    // handle when predicate is false, checking if there is an else block
    if (elseBlock.getChildren().size() > 0) {
      InstPair elseInstBody = visit(elseBlock);
      jumpInstr.setNext(0, elseInstBody.getStart());
      elseInstBody.getEnd().setNext(0, exitInstr);
    }
    else {
      Instruction nop = new NopInst();
      jumpInstr.setNext(0, nop);
      nop.setNext(0, exitInstr);
    }

    predicate.getEnd().getNext(0).getNext(1).getNext(0);
    return new InstPair(predicate.getStart(), exitInstr);
  }

  /**
   * Implement for loops.
   */
  @Override
  public InstPair visit(For loop) {
    Instruction exitInstr = new NopInst();
    currLoopExit = exitInstr;

    InstPair initInstr = visit(loop.getInit()); // you have a variable declaration here
    InstPair condInstr = visit(loop.getCond()); // a condition instruction here
    InstPair incrInstr = visit(loop.getIncrement()); // an operaion header
    InstPair bodyInstr = visit(loop.getBody());

    // set global variable to keep track of the exitInstruction for the loop
    JumpInst jumpInstruction = new JumpInst(condInstr.getValue());

    // begin constructing cfg for for-loop
    initInstr.getEnd().setNext(0, condInstr.getStart());
    condInstr.getEnd().setNext(0, jumpInstruction);

    // handle when condition is true
    jumpInstruction.setNext(1, bodyInstr.getStart());
    bodyInstr.getEnd().setNext(0, incrInstr.getStart());
    incrInstr.getEnd().setNext(0, condInstr.getStart());

    // handle when the condition is false
    jumpInstruction.setNext(0, exitInstr); 

    // remember to increment inLoop counter
    return new InstPair(initInstr.getStart(), exitInstr);
  }
}

package crux.ast;

import crux.ast.*;
import crux.ast.OpExpr.Operation;
import crux.pt.CruxBaseVisitor;
import crux.pt.CruxParser;
import crux.ast.types.*;
import crux.ast.SymbolTable.Symbol;
import org.antlr.v4.runtime.ParserRuleContext;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


/**
 * This class will convert the parse tree generated by ANTLR to AST It follows the visitor pattern
 * where decls will be resolved by DeclVisitor Class, Stmts will be resolved by StmtVisitor Class, 
 * Exprs will be resolved by ExprVisitor Class
 */

public final class ParseTreeLower {
  private final DeclVisitor declVisitor = new DeclVisitor();
  private final StmtVisitor stmtVisitor = new StmtVisitor();
  private final ExprVisitor exprVisitor = new ExprVisitor();

  private final SymbolTable symTab;

  public ParseTreeLower(PrintStream err) {
    symTab = new SymbolTable(err);
  }

  private static Position makePosition(ParserRuleContext ctx) {
    var start = ctx.start;
    return new Position(start.getLine());
  }

  /**
   *
   * @return True if any errors
   */
  public boolean hasEncounteredError() {
    return symTab.hasEncounteredError();
  }


  /**
   * Lower top-level parse tree to AST
   *
   * @return a {@link DeclList} object representing the top-level AST.
   */

  public DeclarationList lower(CruxParser.ProgramContext program) {
    // Iterate through each child of program and call visit. This should return a Declaration.
    // Put all of these in the declaration list
    ArrayList<Declaration> decls = new ArrayList<Declaration>();
    for (CruxParser.DeclContext ctx : program.declList().decl()) {
      Declaration decl = ctx.accept(declVisitor);
      decls.add(decl);
    }

    return new DeclarationList(makePosition(program), decls);
  }

  /**
   * Lower stmt list by lower individual stmt into AST.
   *
   * @return a {@link StmtList} AST object.
   */
  private StatementList lower(CruxParser.StmtListContext stmtList) { 
    ArrayList<Statement> stmts = new ArrayList<Statement>();
    for (CruxParser.StmtContext ctx : stmtList.stmt()) {
      Statement stmt = ctx.accept(stmtVisitor);
      if (stmt != null) {
        stmts.add(stmt); 
      }
    }
    return new StatementList(makePosition(stmtList), stmts);
  }
   

  /**
   * Similar to {@link #lower(CruxParser.StmtListContext)}, but handles symbol table as well.
   *
   * @return a {@link StmtList} AST object.
   */

  
  private StatementList lower(CruxParser.StmtBlockContext stmtBlock) { 
    ArrayList<Statement> stmts = new ArrayList<Statement>();
    for (CruxParser.StmtContext ctx : stmtBlock.stmtList().stmt()) {
      Statement stmt = ctx.accept(stmtVisitor);
      if (stmt != null) {
        stmts.add(stmt);
      }
    }
    
    return new StatementList(makePosition(stmtBlock), stmts);
  }
   
  /**
   * A parse tree visitor to create AST nodes derived from {@link Declaration}
   */
  private final class DeclVisitor extends CruxBaseVisitor<Declaration> {

    /* Creates a type object based off of input String */
    public Type makeType(String name) {
      if (name.equals("bool")) {
        return new BoolType();
      }
      else if (name.equals("int")) {
        return new IntType();
      }
      return new ErrorType("Invalid Type: " + name);
    }

    /* Creates an array type object based off of input String */
    public Type makeArrayType(String name, long size) {
      return new ArrayType(size, makeType(name));
    }

    /* Creates a type object for functions based off of input String */
    public Type makeFuncType(CruxParser.FunctionDefnContext ctx) {
      String name = ctx.type().Identifier().getText();
      ArrayList<Type> paramList = new ArrayList<Type>();
      for (CruxParser.ParamContext param : ctx.paramList().param()) {
        paramList.add(makeType(param.type().Identifier().getText()));
      }
      
      if (name.equals("void")) {
        return new FuncType(new TypeList(paramList), new VoidType());
      }
      else if (name.equals("bool")) {
        return new FuncType(new TypeList(paramList), new BoolType());
      }
      else if (name.equals("int")) {
        return new FuncType(new TypeList(paramList), new IntType());
      }
      return new FuncType(new TypeList(paramList), new ErrorType("Function type '" + name + "'' is not valid"));
    }

    /**
     * Visit a parse tree var decl and create an AST {@link VarariableDeclaration}
     *
     * @return an AST {@link VariableDeclaration}
     */

    @Override
    public VariableDeclaration visitVarDecl(CruxParser.VarDeclContext ctx) {
      Type ctxType = makeType(ctx.type().Identifier().getText());
      String ctxIden = ctx.Identifier().getText();
      Position pos = makePosition(ctx);

      Symbol sym = symTab.add(pos, ctxIden, ctxType);

      return new VariableDeclaration(pos, sym);
    }

    /**
     * Visit a parse tree array decl and creates an AST {@link ArrayDeclaration}
     *
     * @return an AST {@link ArrayDeclaration}
     */
    @Override
    public Declaration visitArrayDecl(CruxParser.ArrayDeclContext ctx) {
      long ctxSize = Long.parseLong(ctx.Integer().getText());
      Type ctxType = makeArrayType(ctx.type().Identifier().getText(), ctxSize);
      String ctxIden = ctx.Identifier().getText();
      Position pos = makePosition(ctx);

      Symbol sym = symTab.add(pos, ctxIden, ctxType);

      return new ArrayDeclaration(pos, sym);
    }

    /**
     * Visit a parse tree function definition and create an AST {@link FunctionDefinition}
     *
     * @return an AST {@link FunctionDefinition}
     */

     @Override
     public Declaration visitFunctionDefn(CruxParser.FunctionDefnContext ctx) {
        Type ctxType = makeFuncType(ctx);
        String ctxIden = ctx.Identifier().getText();
        Position pos = makePosition(ctx);
        Symbol sym = symTab.add(pos, ctxIden, ctxType);
        
        // Enter a new scope. Parameters and local variables will go here
        symTab.enter();

        // Handle each parameter and add to list
        // According to crux grammar, a parameter cannot be an array so this
        // should be handled just like a variable declaration. Must be handled
        // manually since data is a ParamContext instead of VarDeclContext
        ArrayList<Symbol> symbols = new ArrayList<Symbol>();
        for (CruxParser.ParamContext context : ctx.paramList().param()) {
          Type currType = makeType(context.type().Identifier().getText());
          String currIden = context.Identifier().getText();
          Position currPos = makePosition(context);

          Symbol currSym = symTab.add(currPos, currIden, currType);
          symbols.add(currSym);
        }
       
        // Handle each statement and add to List
        ArrayList<Statement> statements = new ArrayList<Statement>();
        for (CruxParser.StmtContext context : ctx.stmtBlock().stmtList().stmt()) {
          Statement stmt = context.accept(stmtVisitor);
          if (stmt != null) {
            statements.add(stmt);
          }
        }
        StatementList sList = new StatementList(makePosition(ctx.stmtBlock()), statements);

        // Exit the function scope
        symTab.exit();

        return new FunctionDefinition(pos, sym, symbols, sList);
     } 
  }


  /**
   * A parse tree visitor to create AST nodes derived from {@link Stmt}
   */

  private final class StmtVisitor extends CruxBaseVisitor<Statement> {
    /**
     * Visit a parse tree var decl and create an AST {@link VariableDeclaration}. Since
     * {@link VariableDeclaration} is both {@link Declaration} and {@link Statement}, we simply
     * delegate this to {@link DeclVisitor#visitArrayDecl(CruxParser.ArrayDeclContext)} which we
     * implement earlier.
     *
     * @return an AST {@link VariableDeclaration}
     */
    @Override
    public Statement visitVarDecl(CruxParser.VarDeclContext ctx) { 
      return declVisitor.visitVarDecl(ctx);
    }
 
    
    /**
     * Visit a parse tree assignment stmt and create an AST {@link Assignment}
     *
     * @return an AST {@link Assignment}
     */
    @Override
    public Statement visitAssignStmt(CruxParser.AssignStmtContext ctx) {
      Position pos = makePosition(ctx);
      Expression des = exprVisitor.visitDesignator(ctx.designator());
      Expression expr0 = exprVisitor.visitExpr0(ctx.expr0());

      return new Assignment(pos, des, expr0);
    }
     
    
    /**
     * Visit a parse tree assignment nosemi stmt and create an AST {@link Assignment}
     *
     * @return an AST {@link Assignment}
     */
    @Override
    public Statement visitAssignStmtNoSemi(CruxParser.AssignStmtNoSemiContext ctx) { 
      Position pos = makePosition(ctx);
      Expression des = exprVisitor.visitDesignator(ctx.designator());
      Expression expr0 = exprVisitor.visitExpr0(ctx.expr0());

      return new Assignment(pos, des, expr0);
    }
    

    /**
     * Visit a parse tree call stmt and create an AST {@link Call}. Since {@link Call} is both
     * {@link Expression} and {@link Statementt}, we simply delegate this to
     * {@link ExprVisitor#visitCallExpr(CruxParser.CallExprContext)} that we will implement later.
     *
     * @return an AST {@link Call}
     */
    @Override
    public Statement visitCallStmt(CruxParser.CallStmtContext ctx) { 
      return exprVisitor.visitCallExpr(ctx.callExpr());
    }
     
    
    /**
     * Visit a parse tree if-else branch and create an AST {@link IfElseBranch}. The template code
     * shows partial implementations that visit the then block and else block recursively before
     * using those returned AST nodes to construct {@link IfElseBranch} object.
     *
     * @return an AST {@link IfElseBranch}
     */
    
    @Override
    public Statement visitIfStmt(CruxParser.IfStmtContext ctx) { 
      Expression condition = exprVisitor.visitExpr0(ctx.expr0());

      // Enter a new scope 
      symTab.enter();

      ArrayList<Statement> ifStatements = new ArrayList<Statement>(); 
      for (CruxParser.StmtContext context : ctx.stmtBlock(0).stmtList().stmt()) {
        Statement stmt = context.accept(stmtVisitor);
        if (stmt != null) {
          ifStatements.add(stmt);
        }
      }
      StatementList thenBlock = new StatementList(makePosition(ctx.stmtBlock(0)), ifStatements);

      // Exit if body scope
      symTab.exit();

      // Check if else block exists
      StatementList elseBlock = new StatementList(null, new ArrayList<Statement>());
      if (ctx.stmtBlock().size() > 1) {
        // Enter a new scope
        symTab.enter();
        
        ArrayList<Statement> elseStatements = new ArrayList<Statement>();
        for (CruxParser.StmtContext context : ctx.stmtBlock(1).stmtList().stmt()) {
          Statement stmt = context.accept(stmtVisitor);
          if (stmt != null) {
            elseStatements.add(stmt);
          }
        }
        elseBlock = new StatementList(makePosition(ctx.stmtBlock(1)), elseStatements);

        // Exit else body scope
        symTab.exit();
      }
      
      return new IfElseBranch(makePosition(ctx), condition, thenBlock, elseBlock);
    }
     
    
    /**
     * Visit a parse tree for loop and create an AST {@link For}. You'll going to use a similar
     * techniques as {@link #visitIfStmt(CruxParser.IfStmtContext)} to decompose this construction.
     *
     * @return an AST {@link Loop}
     */
    
    @Override
    public Statement visitForStmt(CruxParser.ForStmtContext ctx) { 
      // Enter a new scope 
      symTab.enter();

      // Handle initialization
      Expression initDesg = exprVisitor.visitDesignator(ctx.assignStmt().designator());
      Expression initExpr = exprVisitor.visitExpr0(ctx.assignStmt().expr0());

      Assignment init = new Assignment(makePosition(ctx.assignStmt()), initDesg, initExpr);
      
      // Handle condition
      Expression condition = exprVisitor.visitExpr0(ctx.expr0());

      // Handle incrementer
      Expression incrDesg = exprVisitor.visitDesignator(ctx.assignStmtNoSemi().designator());
      Expression incrExpr = exprVisitor.visitExpr0(ctx.assignStmtNoSemi().expr0());

      Assignment incr = new Assignment(makePosition(ctx.assignStmtNoSemi()), incrDesg, incrExpr);

      // Handle body
      ArrayList<Statement> bodyStatements = new ArrayList<Statement>(); 
      for (CruxParser.StmtContext context : ctx.stmtBlock().stmtList().stmt()) {
        Statement stmt = context.accept(stmtVisitor);
        if (stmt != null) {
          bodyStatements.add(stmt);
        }
      }
      StatementList body = new StatementList(makePosition(ctx.stmtBlock()), bodyStatements);

      // Exit for body scope
      symTab.exit();


      return new For(makePosition(ctx), init, condition, incr, body);
    }
     

    /**
     * Visit a parse tree return stmt and create an AST {@link Return}. Here we show a simple
     * example of how to lower a simple parse tree construction.
     *
     * @return an AST {@link Return}
     */

    
    @Override
    public Statement visitReturnStmt(CruxParser.ReturnStmtContext ctx) {
      Expression val = exprVisitor.visitExpr0(ctx.expr0());
      
      return new Return(makePosition(ctx), val);
    }
     
    
    /**
     * Creates a Break node
     */
    
    @Override
    public Statement visitBreakStmt(CruxParser.BreakStmtContext ctx) { 
      return new Break(makePosition(ctx));
    }
    
  }

  private final class ExprVisitor extends CruxBaseVisitor<Expression> {
    public Operation makeOp(String op) {
      if (op.equals(">=")) {
        return Operation.GE;
      }
      else if (op.equals("<=")) {
        return Operation.LE;
      }
      else if (op.equals("!=")) {
        return Operation.NE;
      }
      else if (op.equals("==")) {
        return Operation.EQ;
      }
      else if (op.equals(">")) {
        return Operation.GT;
      }
      else if (op.equals("<")) {
        return Operation.LT;
      }
      else if (op.equals("+")) {
        return Operation.ADD;
      }
      else if (op.equals("-")) {
        return Operation.SUB;
      }
      else if (op.equals("*")) {
        return Operation.MULT;
      }
      else if (op.equals("/")) {
        return Operation.DIV;
      }
      else if (op.equals("&&")) {
        return Operation.LOGIC_AND;
      }
      else if (op.equals("||")) {
        return Operation.LOGIC_OR;
      }
      else if (op.equals("!")) {
        return Operation.LOGIC_NOT;
      }
      return null;
    }

    /**
     * Parse Expr0 to OpExpr Node Parsing the expr should be exactly as described in the grammar
     */
    @Override
    public Expression visitExpr0(CruxParser.Expr0Context ctx) {
      Position pos = makePosition(ctx);

      if (ctx.op0() != null) {
        Expression left = visit(ctx.expr1(0));
        Expression right = visit(ctx.expr1(1));
        Operation op = makeOp(ctx.op0().getText());
        return new OpExpr(pos, op, left, right);
      }
      else {
        return visit(ctx.expr1(0));
      }
    }

    /**
     * Parse Expr1 to OpExpr Node Parsing the expr should be exactly as described in the grammar
     */
    @Override
    public Expression visitExpr1(CruxParser.Expr1Context ctx) {
      Position pos = makePosition(ctx);

      if (ctx.op1() != null) {
        Expression left = visit(ctx.expr1()); 
        Expression right = visit(ctx.expr2());
        Operation op = makeOp(ctx.op1().getText());
        return new OpExpr(pos, op, left, right);
      }
      else {
        return visit(ctx.expr2());
      }
    }

    /**
     * Parse Expr2 to OpExpr Node Parsing the expr should be exactly as described in the grammar
     */
    @Override
    public Expression visitExpr2(CruxParser.Expr2Context ctx) {
      Position pos = makePosition(ctx);

      if (ctx.op2() != null) {
        Expression left = visit(ctx.expr2());
        Expression right = visit(ctx.expr3());
        Operation op = makeOp(ctx.op2().getText());
        return new OpExpr(pos, op, left, right);
      }
      else {
        return visit(ctx.expr3());
      }
    }

    /**
     * Parse Expr3 to OpExpr Node Parsing the expr should be exactly as described in the grammar
     */
    @Override
    public Expression visitExpr3(CruxParser.Expr3Context ctx) {
      Position pos = makePosition(ctx);

      if (ctx.expr3() != null) {     
        Expression left = visit(ctx.expr3());
        Operation op = makeOp("!");
        return new OpExpr(pos, op, left, null);
      }
      else if (ctx.expr0() != null) { 
        return visit(ctx.expr0());
      }
      else if (ctx.designator() != null) { 
        return visit(ctx.designator());
      }
      else if (ctx.callExpr() != null) {
       return visit(ctx.callExpr());
      }
      else { // ctx.literal() != null
        return visit(ctx.literal());
      }
    }

    /**
     * Create an Call Node
     */
    @Override
    public Call visitCallExpr(CruxParser.CallExprContext ctx) {
      Position pos = makePosition(ctx);
      String ctxIden = ctx.Identifier().getText();
      Symbol calleeSym = symTab.lookup(pos, ctxIden);
      
      // Create a list of Expression by iterating through ExprListContext
      ArrayList<Expression> args = new ArrayList<Expression>();
      for (CruxParser.Expr0Context exprContext : ctx.exprList().expr0()) {
        args.add(visit(exprContext));
      }

      return new Call(pos, calleeSym, args);
    }

    /**
     * visitDesignator will check for a name or ArrayAccess FYI it should account for the case when
     * the designator was dereferenced 
     */
    @Override
    public Expression visitDesignator(CruxParser.DesignatorContext ctx) {
      Position pos = makePosition(ctx);
      String ctxIden = ctx.Identifier().getText();
      Symbol sym = symTab.lookup(pos, ctxIden);

      if (ctx.expr0() != null) {
        Expression expr = visit(ctx.expr0());
        return new ArrayAccess(pos, sym, expr);
      }
  
      return new VarAccess(pos, sym);
    }

    /**
     * Create an Literal Node
     */
    @Override
    public Expression visitLiteral(CruxParser.LiteralContext ctx) {
      Position pos = makePosition(ctx);

      if (ctx.Integer() != null) {
        Long value = Long.parseLong(ctx.Integer().getText());
        return new LiteralInt(pos, value);
      }
      else if (ctx.True() != null) {
        return new LiteralBool(pos, true);
      }
      else {// ctx.False() != null
        return new LiteralBool(pos, false);
      }
    }
  }
}

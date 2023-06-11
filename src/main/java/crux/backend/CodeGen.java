package crux.backend;

import crux.ast.SymbolTable.Symbol;
import crux.ir.*;
import crux.ir.insts.*;
import crux.printing.IRValueFormatter;

import crux.ast.types.*;

import java.util.*;

/**
 * Convert the CFG into Assembly Instructions
 */
public final class CodeGen extends InstVisitor {
  private final Program p;
  private final CodePrinter out;

  private int labelCount[] = new int[1];

  private HashMap<Variable, Integer> varIndexMap = new HashMap<Variable, Integer>();
  private HashMap<Instruction, String> labelMap = new HashMap<Instruction, String>();
  private int varIndex = 1; 
  private int callParamsNum = 0;

  public CodeGen(Program p) {
    this.p = p;
    // Do not change the file name that is outputted or it will
    // break the grader!

    out = new CodePrinter("a.s");
  }

  /**
   * It should allocate space for globals call genCode for each Function
   */
  public void genCode() {
    // Iterate through each global and generate the x86 decl
    for (Iterator<GlobalDecl> it = p.getGlobals(); it.hasNext();) {
      GlobalDecl g = it.next();
      String gName = g.getSymbol().getName();
      long gSize = g.getNumElement().getValue() * 8;
      out.printCode(".comm " + gName + ", " + gSize + ", 8");
    }

    // Iterate through each function and generate code
    for (Iterator<Function> it = p.getFunctions(); it.hasNext();) {
      Function f = it.next();
      genFuncCode(f);
    }
    
    out.close();
  }

  private void genFuncCode(Function f) {
    varIndexMap.clear();
    labelMap.clear();
    varIndex = 1;

    // 1. assign any lables for this function. The map variable will hold the
    // mapping between an instruction and its label
    labelMap = f.assignLabels(labelCount);

    // 2. Declare and print function label
    out.printCode(".globl " + f.getName());
    out.printLabel(f.getName() + ":");

    // 3. Adjust stack space for local vars
    int numVars = f.getNumTempVars() + f.getNumTempAddressVars();
    numVars = numVars % 2 == 1 ? numVars + 1 : numVars;
    out.printCode("enter $(8 * " + numVars + "), $0");

    // 4. Handle function arguments: 
    // first 6 args are in %rdi, %rsi, %rdx, %rcx, %r8, and %r9.
    // These need to be moved to the stack as local vars. 
    // If more than 6 args, the rest will be above stack frame
    List<LocalVar> args = f.getArguments();
    for (int i = 0; i < args.size(); ++i) {
      switch (i) {
        case 0:
          out.printCode("movq %rdi, -8(%rbp)");
          break;
        case 1:
          out.printCode("movq %rsi, -16(%rbp)");
          break;
        case 2:
          out.printCode("movq %rdx, -24(%rbp)");
          break;
        case 3:
          out.printCode("movq %rcx, -32(%rbp)");
          break;
        case 4:
          out.printCode("movq %r8, -40(%rbp)");
          break;
        case 5:
          out.printCode("movq %r9, -48(%rbp)");
          break;
        default:
          out.printCode("movq 8*" + (i + 1) + "-40(%rbp), %r10"); 
          out.printCode("movq %r10, -8*" + (i + 1) + "(%rbp)");
      }
      varIndexMap.put(args.get(i), varIndex);
      ++varIndex;
    }

    // 5. Generate code for function body
    // Note: for linearizing, jump to true, else continue
    Stack<Instruction> tovisit = new Stack<>();
    HashSet<Instruction> visited = new HashSet<>();
    if (f.getStart() != null) {
      tovisit.push(f.getStart());
    }
    while (!tovisit.isEmpty()) {
      Instruction inst = tovisit.pop();

      // Handle label
      if (labelMap.get(inst) != null) {
        out.printLabel(labelMap.get(inst) + ":");
      }

      // Visit instruction
      inst.accept(this);

      // If last instruction in cfg
      if (inst.numNext() == 0) {
        // 6. Leaving function
        out.printCode("leave");
        out.printCode("ret");
      }

      // Iterate through all next instructions and add to stack if not visited yet
      for (int i = inst.numNext() - 1; i >= 0; --i) {
        Instruction next = inst.getNext(i);
        if (!visited.contains(next)) {
          tovisit.push(next);
          visited.add(next);
        }
        else {
          // Label already visited, we must unconditionally jump to the block
          String mergeLabel = labelMap.get(next);
          out.printCode("jmp " + mergeLabel);
        }
      }
    }
  }

  /*
   * An AddressAt instruction is called only on global array access
   */
  public void visit(AddressAt i) {
    // Problem: I am putting the address in stack again even if it is already there. Only run this when not already there
    AddressVar var = i.getDst();
    checkStack(var);
    int dst_offset = varIndexMap.get(var);

    // 1. Copy base address of varName to %r11
    String varName = i.getBase().getName();
    out.printCode("movq " + varName + "@GOTPCREL(%rip), %r11"); 

    // 2. Calulate the address with offset
    LocalVar offset = i.getOffset();
    if (offset != null) {
      int idx = varIndexMap.get(offset);
      out.printCode("movq -8*" + idx + "(%rbp), %r10");   // access offset and store in %r10
      out.printCode("imulq $8, %r10");                  // %r10 = offset * 8
      out.printCode("addq %r10, %r11");                 // address = base + offset
    }
    
    // 3. Store address in the destination
    out.printCode("movq %r11, -8*" + dst_offset + "(%rbp)");
  }

  public void visit(BinaryOperator i) {
    BinaryOperator.Op op = i.getOperator();
    int lhs_offset = varIndexMap.get(i.getLeftOperand());
    int rhs_offset = varIndexMap.get(i.getRightOperand());

    LocalVar destVar = i.getDst();
    
    // Check if var is already on the stack
    checkStack(destVar);
    int dst_offset = varIndexMap.get(destVar);

    if (op == BinaryOperator.Op.Add) {
      out.printCode("movq -8*" + lhs_offset + "(%rbp), %r10");
      out.printCode("addq -8*" + rhs_offset + "(%rbp), %r10");
    }
    else if (op == BinaryOperator.Op.Sub) {
      out.printCode("movq -8*" + lhs_offset + "(%rbp), %r10");
      out.printCode("subq -8*" + rhs_offset + "(%rbp), %r10");
    }
    else if (op == BinaryOperator.Op.Mul) {
      out.printCode("movq -8*" + lhs_offset + "(%rbp), %r10");
      out.printCode("imulq -8*" + rhs_offset + "(%rbp), %r10");
    }
    else if (op == BinaryOperator.Op.Div) {
      // lhs value is moved to %rax and converted to an octoword in %rdx:%rax
      out.printCode("movq -8*" + lhs_offset + "(%rbp), %rax");
      out.printCode("cqto");
      // %rdx:%rax is divided by rhs_value and quotient is stored in %rax
      out.printCode("idivq -8*" + rhs_offset + "(%rbp)");
      out.printCode("movq %rax, -8*" + dst_offset + "(%rbp)");
      return;
    }

    // Store result in destination
    out.printCode("movq %r10, -8*" + dst_offset + "(%rbp)");
  }

  public void visit(CompareInst i) {
    CompareInst.Predicate p = i.getPredicate();
    int lhs_offset = varIndexMap.get(i.getLeftOperand());
    int rhs_offset = varIndexMap.get(i.getRightOperand());

    out.printCode("movq $1, %r10"); // contains true
    out.printCode("movq $0, %rax"); // contains false
    out.printCode("movq -8*" + lhs_offset + "(%rbp), %r11");
    out.printCode("cmp -8*" + rhs_offset + "(%rbp), %r11");

    // "true" will be saved in %rax if the comparison succeeds
    // otherwise %rax stays as "false"
    if (p == CompareInst.Predicate.GE) {
      out.printCode("cmovge %r10, %rax");
    }
    else if (p == CompareInst.Predicate.GT) {
      out.printCode("cmovg %r10, %rax");
    }
    else if (p == CompareInst.Predicate.LE) {
      out.printCode("cmovle %r10, %rax");
    }
    else if (p == CompareInst.Predicate.LT) {
      out.printCode("cmovl %r10, %rax");
    }
    else if (p == CompareInst.Predicate.EQ) {
      out.printCode("cmove %r10, %rax");
    }
    else if (p == CompareInst.Predicate.NE) {
      out.printCode("cmovne %r10, %rax");
    }
    
    // Store result of comparison in destination 
    LocalVar dest = i.getDst();
    checkStack(dest);
    int dst_offset = varIndexMap.get(dest);
    out.printCode("movq %rax, -8*" + dst_offset + "(%rbp)");
  }

  public void visit(CopyInst i) {
    Value srcVal = i.getSrcValue();
    Variable v = i.getDstVar();
    
    // First need to check if v is alread on the stack
    checkStack(v);
    int to_offset = varIndexMap.get(v);

    // If copying an integer constant    
    if (srcVal.getClass() == IntegerConstant.class) {
      long val = ((IntegerConstant) srcVal).getValue();

      out.printCode("movq $" + val + ", -8*" + to_offset + "(%rbp)");
      return;
    }

    // If copying a boolean constant
    else if (srcVal.getClass() == BooleanConstant.class) {
      boolean val = ((BooleanConstant) srcVal).getValue();

      if (val) {
        out.printCode("movq $1" + ", -8*" + to_offset + "(%rbp)");
      }
      else {
        out.printCode("movq $0" + ", -8*" + to_offset + "(%rbp)");
      }
      return;
    }
    // Otherwise, we have a localvar
    int from_offset = varIndexMap.get(srcVal);
    out.printCode("movq -8*" + from_offset + "(%rbp), %r10");
    out.printCode("movq %r10, -8*" + to_offset + "(%rbp)");
  }

  /**
   * The JumpInst is only used for conditions in if branch and for branch.
   */
  public void visit(JumpInst i) {
    // Conditional jump when condition is true
    int p_offset = varIndexMap.get(i.getPredicate());
    out.printCode("movq -8*" + p_offset + "(%rbp), %r10");
    out.printCode("cmp $1, %r10");  
    String thenLabel = labelMap.get(i.getNext(1)); 
    out.printCode("je " + thenLabel);
  }

  public void visit(LoadInst i) {
    int src_offset = varIndexMap.get(i.getSrcAddress());
    
    LocalVar destVar = i.getDst();
    checkStack(destVar);
    int dst_offset = varIndexMap.get(destVar);
    
    out.printCode("movq -8*" + src_offset + "(%rbp), %r11");
    out.printCode("movq 0(%r11), %r10");
    out.printCode("movq %r10, -8*" + dst_offset + "(%rbp)");
  }

  public void visit(NopInst i) { /* does nothing */ }

  public void visit(StoreInst i) {
    int src_offset = varIndexMap.get(i.getSrcValue());

    AddressVar destVar = i.getDestAddress();
    checkStack(destVar);
    int dst_offset = varIndexMap.get(destVar);
  
    out.printCode("movq -8*" + dst_offset + "(%rbp), %r11");
    out.printCode("movq -8*" + src_offset + "(%rbp), %r10");
    out.printCode("movq %r10, 0(%r11)");
  }

  public void visit(ReturnInst i) {
    // Stores return value in %rax
    LocalVar retValue = i.getReturnValue();
    int ret_offset = varIndexMap.get(retValue);
    out.printCode("movq -8*" + ret_offset + "(%rbp), %rax");

    // Deallocate stack space used when parameters > 6
    for (int j = callParamsNum; j > 6; --j) {
      out.printCode("pop %r10"); //discard values
    }
    if (callParamsNum - 6 > 0 && callParamsNum % 2 == 1) {
      out.printCode("pop %r10");
    }

    out.printCode("leave");
    out.printCode("ret");
  }

  /*
   * Parameter's values for a function call is stored in the stack. 
   * Look into VarIndexMap to find the offset corresponding to stack location.
   */
  public void visit(CallInst i) {
    int offset;

    // 1. store parameters in designated registers or stack
    // The first six params will be stored in %rdi, %rsi, %rdx, %rcx, %r8, and %r9 respectively
    // When more than 6 params, store above stack frame starting at 16(%rbp)
    List<LocalVar> params = i.getParams(); // parameter names
    callParamsNum = params.size();
    
    // Make sure we will still be 16 byte alligned
    if (callParamsNum - 6 > 0 && callParamsNum % 2 == 1) {
      out.printCode("push $0");
    }

    for (int j = 1; j <= callParamsNum; ++j) {
      offset = varIndexMap.get(params.get(j-1));

      switch (j) {
        case 1:
          out.printCode("movq -8*" + offset + "(%rbp), %rdi");
          break;
        case 2:
          out.printCode("movq -8*" + offset + "(%rbp), %rsi");
          break;
        case 3:
          out.printCode("movq -8*" + offset + "(%rbp), %rdx");
          break;
        case 4:
          out.printCode("movq -8*" + offset + "(%rbp), %rcx");
          break;
        case 5:
          out.printCode("movq -8*" + offset + "(%rbp), %r8");
          break;
        case 6:
          out.printCode("movq -8*" + offset + "(%rbp), %r9");     
          break;
      }

      // push in reverse order
      if (j > 6) { 
        offset = varIndexMap.get(params.get(callParamsNum - j + 6)); 
        out.printCode("push -8*" + offset + "(%rbp)");
      }
    }

    // 2. Call instruction
    out.printCode("call " + i.getCallee().getName());

    // 3. If the function is not void, the return value is in %rax,
    // and we store the result in the destination
    String t = ((FuncType) i.getCallee().getType()).getRet().toString();
    if (!t.equals("void")) {
      LocalVar dest = i.getDst();
      checkStack(dest);
      int dst_offset = varIndexMap.get(dest);
      out.printCode("movq %rax, -8*" + dst_offset + "(%rbp)");
    }
  }

  public void visit(UnaryNotInst i) {
    // Calculate the unary
    int val_offset = varIndexMap.get(i.getInner());
    out.printCode("movq $1, %r11");
    out.printCode("subq -8*" + val_offset + "(%rbp), %r11"); // substract value from 1

    // Store result in destination 
    LocalVar dest = i.getDst();
    checkStack(dest);
    int dst_offset = varIndexMap.get(dest);
    out.printCode("movq %r11, -8*" + dst_offset + "(%rbp)");
  }

  private void checkStack(Variable v) {
    if (varIndexMap.get(v) == null) {
      varIndexMap.put(v, varIndex);
      varIndex += 1;
    }
  }
  
}

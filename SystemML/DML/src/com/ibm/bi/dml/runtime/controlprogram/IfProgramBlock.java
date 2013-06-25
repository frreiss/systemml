package com.ibm.bi.dml.runtime.controlprogram;

import java.util.ArrayList;

import com.ibm.bi.dml.parser.IfStatementBlock;
import com.ibm.bi.dml.parser.Expression.ValueType;
import com.ibm.bi.dml.runtime.instructions.Instruction;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.BooleanObject;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.CPInstruction;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.ComputationCPInstruction;
import com.ibm.bi.dml.runtime.instructions.CPInstructions.CPInstruction.CPINSTRUCTION_TYPE;
import com.ibm.bi.dml.runtime.instructions.Instruction.INSTRUCTION_TYPE;
import com.ibm.bi.dml.runtime.instructions.SQLInstructions.SQLScalarAssignInstruction;
import com.ibm.bi.dml.utils.DMLRuntimeException;
import com.ibm.bi.dml.utils.DMLUnsupportedOperationException;


public class IfProgramBlock extends ProgramBlock {

	private ArrayList<Instruction> _predicate;
	private String _predicateResultVar;
	private ArrayList <Instruction> _exitInstructions ;
	private ArrayList<ProgramBlock> _childBlocksIfBody;
	private ArrayList<ProgramBlock> _childBlocksElseBody;
	
	public IfProgramBlock(Program prog, ArrayList<Instruction> predicate) throws DMLRuntimeException{
		super(prog);
		
		_childBlocksIfBody = new ArrayList<ProgramBlock>();
		_childBlocksElseBody = new ArrayList<ProgramBlock>();
		
		_predicate = predicate;
		_predicateResultVar = findPredicateResultVar ();
		_exitInstructions = new ArrayList<Instruction>();
	}
	
	public ArrayList<ProgramBlock> getChildBlocksIfBody()
		{ return _childBlocksIfBody; }

	public void setChildBlocksIfBody(ArrayList<ProgramBlock> blocks)
		{ _childBlocksIfBody = blocks; }
	
	public void addProgramBlockIfBody(ProgramBlock pb)
		{ _childBlocksIfBody.add(pb); }	
	
	public ArrayList<ProgramBlock> getChildBlocksElseBody()
		{ return _childBlocksElseBody; }

	public void setChildBlocksElseBody(ArrayList<ProgramBlock> blocks)
		{ _childBlocksElseBody = blocks; }
	
	public void addProgramBlockElseBody(ProgramBlock pb)
		{ _childBlocksElseBody.add(pb); }
	
	public void printMe() {
		
		System.out.println("***** if current block predicate inst: *****");
		for (Instruction cp : _predicate){
			cp.printMe();
		}
		
		System.out.println("***** children block inst --- if body : *****");
		for (ProgramBlock pb : this._childBlocksIfBody){
			pb.printMe();
		}
	
		System.out.println("***** children block inst --- else body : *****");
		for (ProgramBlock pb: this._childBlocksElseBody){
			pb.printMe();
		}
		
		System.out.println("***** current block inst exit: *****");
		for (Instruction i : this._exitInstructions) {
			i.printMe();
		}	
	}


	public void setExitInstructions2(ArrayList<Instruction> exitInstructions){
		_exitInstructions = exitInstructions;
	}

	public void setExitInstructions1(ArrayList<Instruction> predicate){
		_predicate = predicate;
	}
	
	public void addExitInstruction(Instruction inst){
		_exitInstructions.add(inst);
	}
	
	public ArrayList<Instruction> getPredicate(){
		return _predicate;
	}
	
	public String getPredicateResultVar(){
		return _predicateResultVar;
	}
	
	public void setPredicateResultVar(String resultVar) {
		_predicateResultVar = resultVar;
	}
	
	public ArrayList<Instruction> getExitInstructions(){
		return _exitInstructions;
	}
	
	private BooleanObject executePredicate(ExecutionContext ec) 
		throws DMLRuntimeException, DMLUnsupportedOperationException 
	{
		SymbolTable symb = ec.getSymbolTable();
		BooleanObject result = null;
		try
		{
			if( _predicate!=null && _predicate.size()>0 )
			{
				if( _sb!=null )
				{
					IfStatementBlock isb = (IfStatementBlock)_sb;
					result = (BooleanObject) executePredicate(_predicate, isb.getPredicateHops(), ValueType.BOOLEAN, ec);
				}
				else
					result = (BooleanObject) executePredicate(_predicate, null, ValueType.BOOLEAN, ec);
			}
			else
				result = (BooleanObject)symb.getScalarInput(_predicateResultVar, ValueType.BOOLEAN);
		}
		catch(Exception ex)
		{
			LOG.trace("\nIf predicate variables: "+ symb.get_variableMap().toString());
			throw new DMLRuntimeException(this.printBlockErrorLocation() + "Failed to evaluate the IF predicate.", ex);
		}
		
		if ( result == null )
			throw new DMLRuntimeException(this.printBlockErrorLocation() + "Failed to evaluate the IF predicate.");
		
		return result;
		
		

	}
	
	public void execute(ExecutionContext ec) throws DMLRuntimeException, DMLUnsupportedOperationException{
		
		SymbolTable symb = ec.getSymbolTable();
		
		BooleanObject predResult = executePredicate(ec); 

		if(predResult.getBooleanValue())
		{	
			initST_ifBody(symb);
			for(int i=0; i < _childBlocksIfBody.size(); i++) {
				ProgramBlock pb = _childBlocksIfBody.get(i);
				
				SymbolTable childSymb = symb.getChildTable(i);
				childSymb.copy_variableMap(symb.get_variableMap());
				ec.setSymbolTable(childSymb);
				
				try {
					pb.execute(ec);
				}
				catch(Exception e){
					throw new DMLRuntimeException(this.printBlockErrorLocation() + "Error evaluating if statement body ", e);
				}
				
				symb.set_variableMap( ec.getSymbolTable().get_variableMap() );
				ec.setSymbolTable(symb);
				//_variables = pb._variables;
			}
		}
		else
		{
			initST_elseBody(symb);
			for(int i=0; i < _childBlocksElseBody.size(); i++) {
				ProgramBlock pb = _childBlocksElseBody.get(i);
				
				SymbolTable childSymb = symb.getChildTable(i);
				childSymb.copy_variableMap(symb.get_variableMap());
				ec.setSymbolTable(childSymb);
				
				try {
					pb.execute(ec);
				}
				catch(Exception e){
					throw new DMLRuntimeException(this.printBlockErrorLocation() + "Error evaluating if statement body ", e);
				}
				
				symb.set_variableMap( ec.getSymbolTable().get_variableMap() );
				ec.setSymbolTable(symb);
				//_variables = pb._variables;
			}
		}
		try { 
			executeInstructions(_exitInstructions, ec);
		}
		catch (Exception e){
			
			throw new DMLRuntimeException(this.printBlockErrorLocation() + "Error evaluating exit instructions ", e);
		}
	}
	
	@Override
	public SymbolTable createSymbolTable() {
		SymbolTable st = new SymbolTable(true);
		// symbol table for IF or ELSE body is initialized 
		// at runtime through calls initST_ifBody() and initST_elseBody() 
		return st;
	}
	
	private void initST_ifBody(SymbolTable st) {
		for(int i=0; i < _childBlocksIfBody.size(); i++) {
			if ( st.get_childTables() == null || st.getChildTable(i) == null )
				st.addChildTable(_childBlocksIfBody.get(i).createSymbolTable());
		}
	}
	
	private void initST_elseBody(SymbolTable st) {
		for(int i=0; i < _childBlocksElseBody.size(); i++) {
			if ( st.get_childTables() == null || st.getChildTable(i) == null )
				st.addChildTable(_childBlocksElseBody.get(i).createSymbolTable());
		}
	}
	
	private String findPredicateResultVar ( ) {
		String result = null;
		for ( Instruction si : _predicate ) {
			if ( si.getType() == INSTRUCTION_TYPE.CONTROL_PROGRAM && ((CPInstruction)si).getCPInstructionType() != CPINSTRUCTION_TYPE.Variable ) {
				result = ((ComputationCPInstruction) si).getOutputVariableName();  
			}
			else if(si instanceof SQLScalarAssignInstruction)
				result = ((SQLScalarAssignInstruction)si).getVariableName();
		}
		return result;
	}
	
	public String printBlockErrorLocation(){
		return "ERROR: Runtime error in if program block generated from if statement block between lines " + _beginLine + " and " + _endLine + " -- ";
	}
	
}
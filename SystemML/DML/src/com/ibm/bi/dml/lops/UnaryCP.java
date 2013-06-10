package com.ibm.bi.dml.lops;

import com.ibm.bi.dml.lops.LopProperties.ExecLocation;
import com.ibm.bi.dml.lops.LopProperties.ExecType;
import com.ibm.bi.dml.lops.compile.JobType;
import com.ibm.bi.dml.parser.Expression.*;
import com.ibm.bi.dml.utils.LopsException;


/**
 * Lop to perform unary scalar operations. Example a = !b
 * 
 */

public class UnaryCP extends Lops {
	public enum OperationTypes {
		NOT, ABS, SIN, COS, TAN, SQRT, LOG, EXP, CAST_AS_SCALAR, CAST_AS_MATRIX, PRINT, NROW, NCOL, LENGTH, ROUND, PRINT2, NOTSUPPORTED
	};
	public static final String CAST_AS_MATRIX_OPCODE = "castAsMatrix";

	OperationTypes operation;

	/**
	 * Constructor to perform a scalar operation
	 * 
	 * @param input
	 * @param op
	 */

	public UnaryCP(Lops input, OperationTypes op, DataType dt, ValueType vt) {
		super(Lops.Type.UnaryCP, dt, vt);
		operation = op;
		this.addInput(input);
		input.addOutput(this);

		/*
		 * This lop is executed in control program.
		 */
		boolean breaksAlignment = false; // this does not carry any information
											// for this lop
		boolean aligner = false;
		boolean definesMRJob = false;
		lps.addCompatibility(JobType.INVALID);
		this.lps.setProperties(inputs, ExecType.CP, ExecLocation.ControlProgram, breaksAlignment, aligner, definesMRJob);
	}

	@Override
	public String toString() {

		return "Operation: " + operation;

	}

	@Override
	public String getInstructions(String input, String output)
			throws LopsException {
		String opString = new String(getExecType() + Lops.OPERAND_DELIMITOR);
		ValueType vtype = this.getInputs().get(0).get_valueType();

		switch (operation) {
		case NOT:
			opString += "!";
			break;

		case ABS:
			opString += "abs";
			break;

		case SIN:
			opString += "sin";
			break;

		case COS:
			opString += "cos";
			break;

		case TAN:
			opString += "tan";
			break;

		case SQRT:
			opString += "sqrt";
			break;

		case LOG:
			opString += "log";
			break;

		case ROUND:
			opString += "round";
			break;

		case EXP:
			opString += "exp";
			break;

		case PRINT:
			opString += "print";
			break;

		case PRINT2:
			opString += "print2";
			break;

		// CAST_AS_SCALAR, NROW, NCOL, LENGTH builtins take matrix as the input
		// and produces a scalar
		case CAST_AS_SCALAR:
			opString += "assignvarwithfile";
			break;
		case CAST_AS_MATRIX:
			opString += CAST_AS_MATRIX_OPCODE;
			break;
		case NROW:
			opString += "nrow";
			vtype = ValueType.STRING;
			break;
		case NCOL:
			opString += "ncol";
			vtype = ValueType.STRING;
			break;
		case LENGTH:
			opString += "length";
			vtype = ValueType.STRING;
			break;

		default:
			throw new LopsException(this.printErrorLocation() + 
					"Instruction not defined for UnaryScalar operation: "
							+ operation);
		}

		StringBuilder sb = new StringBuilder();
		sb.append( opString );
		sb.append( OPERAND_DELIMITOR );
		sb.append( input );
		sb.append( DATATYPE_PREFIX );
		sb.append( getInputs().get(0).get_dataType() );
		sb.append( VALUETYPE_PREFIX );
		sb.append( vtype );
		sb.append( OPERAND_DELIMITOR );
		sb.append( output );
		sb.append( DATATYPE_PREFIX );
		sb.append( get_dataType() );
		sb.append( VALUETYPE_PREFIX );
		sb.append( get_valueType() );
		
		return sb.toString();

	}
}

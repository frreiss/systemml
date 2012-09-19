package com.ibm.bi.dml.hops;

import com.ibm.bi.dml.hops.OptimizerUtils.OptimizationMode;
import com.ibm.bi.dml.hops.OptimizerUtils.OptimizationType;
import com.ibm.bi.dml.lops.Aggregate;
import com.ibm.bi.dml.lops.Group;
import com.ibm.bi.dml.lops.Lops;
import com.ibm.bi.dml.lops.RangeBasedReIndex;
import com.ibm.bi.dml.lops.LopProperties.ExecType;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.parser.Expression.ValueType;
import com.ibm.bi.dml.sql.sqllops.SQLLops;
import com.ibm.bi.dml.utils.HopsException;

//for now only works for range based indexing op
public class IndexingOp extends Hops {

	public IndexingOp(String l, DataType dt, ValueType vt, Hops inpMatrix, Hops inpRowL, Hops inpRowU, Hops inpColL, Hops inpColU) {
		super(Kind.Indexing, l, dt, vt);
		/*
		if(inpRowL==null)
			inpRowL=new DataOp("1", DataType.SCALAR, ValueType.INT, DataOpTypes.PERSISTENTREAD, "1", -1, -1, -1, -1);
		if(inpRowU==null)
			inpRowU=new DataOp(Long.toString(get_dim1()), DataType.SCALAR, ValueType.INT, DataOpTypes.PERSISTENTREAD, Long.toString(get_dim1()), -1, -1, -1, -1);
		if(inpColL==null)
			inpColL=new DataOp("1", DataType.SCALAR, ValueType.INT, DataOpTypes.PERSISTENTREAD, "1", -1, -1, -1, -1);
		if(inpColU==null)
			inpColU=new DataOp(Long.toString(get_dim2()), DataType.SCALAR, ValueType.INT, DataOpTypes.PERSISTENTREAD, Long.toString(get_dim2()), -1, -1, -1, -1);
	*/
		getInput().add(0, inpMatrix);
		getInput().add(1, inpRowL);
		getInput().add(2, inpRowU);
		getInput().add(3, inpColL);
		getInput().add(4, inpColU);
		
		// create hops if one of them is null
		inpMatrix.getParent().add(this);
		inpRowL.getParent().add(this);
		inpRowU.getParent().add(this);
		inpColL.getParent().add(this);
		inpColU.getParent().add(this);
	}

	public Lops constructLops()
			throws HopsException {
		if (get_lops() == null) {
			try {
				ExecType et = optFindExecType();
				if(et == ExecType.MR) {
					RangeBasedReIndex reindex = new RangeBasedReIndex(
							getInput().get(0).constructLops(), getInput().get(1).constructLops(), getInput().get(2).constructLops(),
							getInput().get(3).constructLops(), getInput().get(4).constructLops(), getInput().get(0).get_dim1(), getInput().get(0).get_dim2(),
							get_dataType(), get_valueType(), et);
	
					reindex.getOutputParameters().setDimensions(get_dim1(), get_dim2(), 
							get_rows_in_block(), get_cols_in_block(), getNnz());
					
					reindex.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
					
					Group group1 = new Group(
							reindex, Group.OperationTypes.Sort, DataType.MATRIX,
							get_valueType());
					group1.getOutputParameters().setDimensions(get_dim1(),
							get_dim2(), get_rows_in_block(), get_cols_in_block(), getNnz());
					
					group1.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
	
					Aggregate agg1 = new Aggregate(
							group1, Aggregate.OperationTypes.Sum, DataType.MATRIX,
							get_valueType(), et);
					agg1.getOutputParameters().setDimensions(get_dim1(),
							get_dim2(), get_rows_in_block(), get_cols_in_block(), getNnz());
	
					agg1.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
					
					set_lops(agg1);
				}
				else {
					RangeBasedReIndex reindex = new RangeBasedReIndex(
							getInput().get(0).constructLops(), getInput().get(1).constructLops(), getInput().get(2).constructLops(),
							getInput().get(3).constructLops(), getInput().get(4).constructLops(), get_dim1(), get_dim2(),
							get_dataType(), get_valueType(), et);
					reindex.getOutputParameters().setDimensions(get_dim1(), get_dim2(),
							get_rows_in_block(), get_cols_in_block(), getNnz());
					reindex.setAllPositions(this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
					set_lops(reindex);
				}
			} catch (Exception e) {
				throw new HopsException(this.printErrorLocation() + "In IndexingOp Hop, error constructing Lops -- " + e);
			}

		}
		return get_lops();
	}

	@Override
	public String getOpString() {
		String s = new String("");
		s += "Indexing";
		return s;
	}

	public void printMe() throws HopsException {
		if (get_visited() != VISIT_STATUS.DONE) {
			super.printMe();
			for (Hops h : getInput()) {
				h.printMe();
			}
			;
		}
		set_visited(VISIT_STATUS.DONE);
	}

	public SQLLops constructSQLLOPs() throws HopsException {
		throw new HopsException(this.printErrorLocation() + "constructSQLLOPs should not be called for IndexingOp Hop \n");
	}
	
	@Override
	public boolean allowsAllExecTypes()
	{
		return true;
	}
	
	@Override
	public double computeMemEstimate() {
		
		Hops input = getInput().get(0);
		
		if (dimsKnown()) {
			// Indexing does not affect the sparsity, and the 
			// output sparsity is same as that of the input 
			_outputMemEstimate = OptimizerUtils.estimateSize(get_dim1(), get_dim2(), input.getSparsity() );
		} else {
			if ( OptimizerUtils.getOptMode() == OptimizationMode.ROBUST ){
				// In the worst case, indexing returns the entire matrix
				// therefore, worst case estimate is the size of input matrix 
				_outputMemEstimate = input.getOutputSize();
			}
			else if ( OptimizerUtils.getOptMode() == OptimizationMode.AGGRESSIVE ) {
				// In an average case, we expect indexing will touch 10% of data. 
				_outputMemEstimate = 0.1 * input.getOutputSize();
			}
		}
		
		_memEstimate = getInputOutputSize();
		return _memEstimate;
	}

	@Override
	protected ExecType optFindExecType() throws HopsException {
		
		checkAndSetForcedPlatform();

		if( _etypeForced != null ) 			
			_etype = _etypeForced;
		else if ( OptimizerUtils.getOptType() == OptimizationType.MEMORY_BASED ) {
			_etype = findExecTypeByMemEstimate();
		}
		else if ( getInput().get(0).areDimsBelowThreshold() )
			_etype = ExecType.CP;
		else
			_etype = ExecType.MR;
		
		return _etype;
	}
}

/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2013
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */


package com.ibm.bi.dml.runtime.matrix.operators;

import java.io.Serializable;

import com.ibm.bi.dml.runtime.functionobjects.IndexFunction;

public class ReorgOperator  extends Operator implements Serializable
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2013\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";

	private static final long serialVersionUID = -5322516429026298404L;

	public IndexFunction fn;
	
	public ReorgOperator(IndexFunction p)
	{
		fn=p;
		sparseSafe=true;
	}

}

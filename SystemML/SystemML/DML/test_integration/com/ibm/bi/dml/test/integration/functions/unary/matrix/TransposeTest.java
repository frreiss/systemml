/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2013
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.test.integration.functions.unary.matrix;

import org.junit.Test;

import com.ibm.bi.dml.test.integration.AutomatedTestBase;
import com.ibm.bi.dml.test.integration.TestConfiguration;



public class TransposeTest extends AutomatedTestBase 
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2013\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	
	@Override
	public void setUp() {
		baseDirectory = SCRIPT_DIR + "functions/unary/matrix/";
		availableTestConfigurations.put("TransposeTest",
				new TestConfiguration("TransposeTest", new String[] { "vector", "matrix" }));
	}
	
	@Test
	public void testTranspose() {
		int rows = 10;
		int cols = 10;
		
		TestConfiguration config = availableTestConfigurations.get("TransposeTest");
		config.addVariable("rows", rows);
		config.addVariable("cols", cols);
		
		loadTestConfiguration("TransposeTest");
		
		double[][] vector = getRandomMatrix(rows, 1, 0, 1, 1, -1);
		double[][] transposedVector = new double[1][rows];
		for(int i = 0; i < rows; i++) {
			transposedVector[0][i] = vector[i][0];
		}
		writeInputMatrix("vector", vector);
		writeExpectedMatrix("vector", transposedVector);
		
		double[][] matrix = getRandomMatrix(rows, cols, 0, 1, 1, -1);
		double[][] transposedMatrix = new double[cols][rows];
		for(int i = 0; i < rows; i++) {
			for(int j = 0; j < cols; j++) {
				transposedMatrix[j][i] = matrix[i][j];
			}
		}
		writeInputMatrix("matrix", matrix);
		writeExpectedMatrix("matrix", transposedMatrix);
		
		runTest();
		
		compareResults();
	}
	
}
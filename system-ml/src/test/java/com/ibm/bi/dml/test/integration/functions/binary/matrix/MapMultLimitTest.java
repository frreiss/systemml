/**
 * (C) Copyright IBM Corp. 2010, 2015
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.ibm.bi.dml.test.integration.functions.binary.matrix;

import org.junit.Assert;
import org.junit.Test;

import com.ibm.bi.dml.api.DMLScript.RUNTIME_PLATFORM;
import com.ibm.bi.dml.runtime.matrix.MatrixCharacteristics;
import com.ibm.bi.dml.test.integration.AutomatedTestBase;
import com.ibm.bi.dml.test.integration.TestConfiguration;
import com.ibm.bi.dml.utils.Statistics;

/**
 * Tests the number of mapmult operations that can be piggybacked into the same GMR job.
 *
 */

public class MapMultLimitTest extends AutomatedTestBase 
{
	
	private final static String TEST_NAME = "MapMultLimitTest";
	private final static String TEST_DIR = "functions/binary/matrix/";
	
	private final static int rows1 = 2000;
	private final static int rows2 = 3500;
	private final static int cols = 1500;
	
	private final static double sparsity1 = 0.7;
	
	@Override
	public void setUp() 
	{
		addTestConfiguration(
				TEST_NAME, 
				new TestConfiguration(TEST_DIR, TEST_NAME, 
				new String[] { "C1", "C2" })   ); 
	}
	
	@Test
	public void testMapMultLimit()
	{

		RUNTIME_PLATFORM rtold = rtplatform;
		rtplatform = RUNTIME_PLATFORM.HADOOP;

		try
		{
			TestConfiguration config = getTestConfiguration(TEST_NAME);
			
			/* This is for running the junit test the new way, i.e., construct the arguments directly */
			String HOME = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = HOME + TEST_NAME + ".dml";
			programArgs = new String[]{"-args", 
										HOME + INPUT_DIR + "A",
										HOME + INPUT_DIR + "B1",
										HOME + INPUT_DIR + "B2",
					                    HOME + OUTPUT_DIR + "C1",
					                    HOME + OUTPUT_DIR + "C2"
					                  };
			loadTestConfiguration(config);
	
			//System.out.println("Generating A ...");
			double[][] A = getRandomMatrix(rows1, rows2, 0, 1, sparsity1, 10); 
			MatrixCharacteristics mc = new MatrixCharacteristics(rows1, rows2, -1, -1, -1);
			writeInputMatrixWithMTD("A", A, true, mc);			
			
			//System.out.println("Generating B1 ...");
			double[][] B1 = getRandomMatrix(rows2, cols, 0, 1, 0.4, 10); 
			mc = new MatrixCharacteristics(rows2, cols, -1, -1, -1);
			writeInputMatrixWithMTD("B1", B1, true, mc);

			//System.out.println("Generating B2 ...");
			double[][] B2 = getRandomMatrix(rows2, cols, 0, 1, 0.4, 20); 
			mc = new MatrixCharacteristics(rows2, cols, -1, -1, -1);
			writeInputMatrixWithMTD("B2", B2, true, mc);
	
			//System.out.println("Running test...");
			boolean exceptionExpected = false;
			
			// Expected 3 jobs: 1 Reblock, 2 MapMults
			runTest(true, exceptionExpected, null, 3); 
			//System.out.println("#Jobs: " + Statistics.getNoOfExecutedMRJobs() + ", " + Statistics.getNoOfCompiledMRJobs());
			Assert.assertTrue(Statistics.getNoOfExecutedMRJobs()==3);
		}
		finally
		{
			rtplatform = rtold;
		}
	}
	
}
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

package com.ibm.bi.dml.runtime.transform;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONObject;

import com.google.common.collect.Ordering;
import com.ibm.bi.dml.runtime.transform.MVImputeAgent.MVMethod;
import com.ibm.bi.dml.runtime.util.UtilFunctions;
import com.ibm.bi.dml.utils.JSONHelper;

public class RecodeAgent extends TransformationAgent {
	
	
	private int[] _rcdList = null;
	private int[] _mvrcdList = null;
	private int[] _fullrcdList = null;

	// HashMap< columnID, HashMap<distinctValue, count> >
	private HashMap<Integer, HashMap<String, Long>> _rcdMaps  = new HashMap<Integer, HashMap<String, Long>>();
	
	RecodeAgent(JSONObject parsedSpec) {
		Object obj = JSONHelper.get(parsedSpec,TX_METHOD.RECODE.toString());
		int rcdCount = 0;
		if(obj == null) {
		}
		else {
			JSONArray attrs = (JSONArray) JSONHelper.get((JSONObject)obj,JSON_ATTRS);
			
			_rcdList = new int[attrs.size()];
			for(int i=0; i < _rcdList.length; i++) 
				_rcdList[i] = UtilFunctions.toInt( attrs.get(i) );
			rcdCount = _rcdList.length;
		}
		
		obj = JSONHelper.get(parsedSpec,TX_METHOD.MVRCD.toString());
		if(obj == null) {
		}
		else {
			JSONArray attrs = (JSONArray) JSONHelper.get((JSONObject)obj,JSON_ATTRS);
			_mvrcdList = new int[attrs.size()];
			for(int i=0; i < _mvrcdList.length; i++) 
				_mvrcdList[i] = UtilFunctions.toInt( attrs.get(i) );
			rcdCount += attrs.size();
		}
		
		_fullrcdList = new int[rcdCount];
		int idx = -1;
		if(_rcdList != null)
			for(int i=0; i < _rcdList.length; i++)
				_fullrcdList[++idx] = _rcdList[i]; 
		
		if(_mvrcdList != null)
			for(int i=0; i < _mvrcdList.length; i++)
				_fullrcdList[++idx] = _mvrcdList[i]; 
	}
	
	void prepare(String[] words) {
		if ( _rcdList == null && _mvrcdList == null )
			return;
		
		String w = null;
		for (int colID : _fullrcdList) {
			w = UtilFunctions.unquote(words[colID-1].trim());
			if(_rcdMaps.get(colID) == null ) 
				_rcdMaps.put(colID, new HashMap<String, Long>());
			
			HashMap<String, Long> map = _rcdMaps.get(colID);
			Long count = map.get(w);
			if(count == null)
				map.put(w, new Long(1));
			else
				map.put(w, count+1);
		}
	}
	
	private HashMap<String, Long> handleMVConstant(int colID, MVImputeAgent mvagent, HashMap<String, Long> map)
	{
		if ( mvagent.getMethod(colID) == MVMethod.CONSTANT ) 
		{
			// check if the "replacement" is part of the map. If not, add it.
			String repValue = mvagent.getReplacement(colID);
			if(repValue == null)
				throw new RuntimeException("Expecting a constant replacement value for column ID " + colID);
			
			repValue = UtilFunctions.unquote(repValue);
			Long count = map.get(repValue);
			long mvCount = TransformationAgent._numValidRecords - mvagent.getNonMVCount(colID);
			if(count == null)
				map.put(repValue, mvCount);
			else
				map.put(repValue, count + mvCount);
		}
		return map;
	}
	
	/**
	 * Method to output transformation metadata from the mappers. 
	 * This information is collected and merged by the reducers.
	 * 
	 * @param out
	 * @throws IOException
	 */
	@Override
	public void mapOutputTransformationMetadata(OutputCollector<IntWritable, DistinctValue> out, int taskID, TransformationAgent agent) throws IOException {
		if ( _rcdList == null  && _mvrcdList == null )
			return;
		
		try 
		{ 
			MVImputeAgent mvagent = (MVImputeAgent) agent;
			
			for(int i=0; i < _fullrcdList.length; i++) 
			{
				int colID = _fullrcdList[i];
				HashMap<String, Long> map = _rcdMaps.get(colID);
				
				if(map != null) 
				{
					map = handleMVConstant(colID, mvagent,  map);
					
					IntWritable iw = new IntWritable(colID);
					for(String s : map.keySet()) 
						out.collect(iw, new DistinctValue(s, map.get(s)));
				}
			}
		} catch(Exception e) {
			throw new IOException(e);
		}
	}
	
	/**
	 * Function to output transformation metadata, including: 
	 * - recode maps, 
	 * - number of distinct values, 
	 * - mode, and 
	 * - imputation value (in the case of global_mode)
	 * 
	 * The column for which this function is invoked can be one of the following:
	 * - just recoded						(write .map, .ndistinct, .mode)
	 * - just mv imputed (w/ global_mode)	(write .impute)
	 * - both recoded and mv imputed		(write .map, .ndistinct, .mode, .impute)
	 * 
	 * @param map
	 * @param outputDir
	 * @param colID
	 * @param fs
	 * @param mvagent
	 * @throws IOException
	 */
	private void writeMetadata(HashMap<String,Long> map, String outputDir, int colID, FileSystem fs, MVImputeAgent mvagent, boolean fromCP) throws IOException {
		// output recode maps and mode
		
		String mode = null;
		Long count = null;
		int rcdIndex = 0, modeIndex = 0;
		long maxCount = Long.MIN_VALUE;
		
		boolean isRecoded = (isRecoded(colID) != -1);
		boolean isModeImputed = (mvagent.getMethod(colID) == MVMethod.GLOBAL_MODE);
		
		Path pt=new Path(outputDir+"/Recode/"+ outputColumnNames[colID-1] + RCD_MAP_FILE_SUFFIX);
		BufferedWriter br=null;
		if(isRecoded)
			br = new BufferedWriter(new OutputStreamWriter(fs.create(pt,true)));		

		// remove NA strings
		if ( TransformationAgent.NAstrings != null ) 
		{
			for(String naword : TransformationAgent.NAstrings) 
				map.remove(naword);
		}
		
		if(fromCP)
			map = handleMVConstant(colID, mvagent,  map);
		
		if ( map.size() == 0 ) 
			throw new RuntimeException("Can not proceed since \"" + outputColumnNames[colID-1] + "\" (id=" + colID + ") contains only the missing values, and not a single valid value -- set imputation method to \"constant\".");
		
		// Order entries by category (string) value
		Ordering<String> valueComparator = Ordering.natural();
		List<String> newNames = valueComparator.sortedCopy(map.keySet());

		for(String w : newNames) { //map.keySet()) {
				count = map.get(w);
				++rcdIndex;
				
				// output (w, count, rcdIndex)
				if(br != null)		
					br.write(UtilFunctions.quote(w) + TXMTD_SEP + rcdIndex + TXMTD_SEP + count  + "\n");
				
				if(maxCount < count) {
					maxCount = count;
					mode = w;
					modeIndex = rcdIndex;
				}
				
				// Replace count with recode index (useful when invoked from CP)
				map.put(w, (long)rcdIndex);
		}
		
		if(br != null)		
			br.close();
		
		if ( mode == null ) {
			mode = "";
			maxCount = 0;
		}
		
		if ( isRecoded ) 
		{
			// output mode
			pt=new Path(outputDir+"/Recode/"+ outputColumnNames[colID-1] + MODE_FILE_SUFFIX);
			br=new BufferedWriter(new OutputStreamWriter(fs.create(pt,true)));
			br.write(UtilFunctions.quote(mode) + "," + modeIndex + "," + maxCount );
			br.close();
		
			// output number of distinct values
			pt=new Path(outputDir+"/Recode/"+ outputColumnNames[colID-1] + NDISTINCT_FILE_SUFFIX);
			br=new BufferedWriter(new OutputStreamWriter(fs.create(pt,true)));
			br.write(""+map.size());
			br.close();
		}
		
		if (isModeImputed) 
		{
			pt=new Path(outputDir+"/Impute/"+ outputColumnNames[colID-1] + MV_FILE_SUFFIX);
			br=new BufferedWriter(new OutputStreamWriter(fs.create(pt,true)));
			br.write(colID + "," + UtilFunctions.quote(mode));
			br.close();
		}
		
	}
	
	public void outputTransformationMetadata(String outputDir, FileSystem fs, MVImputeAgent mvagent) throws IOException {
		if(_rcdList == null && _mvrcdList == null )
			return;
		
		for(int i=0; i<_fullrcdList.length; i++) {
			int colID = _fullrcdList[i];
			writeMetadata(_rcdMaps.get(colID), outputDir, colID, fs, mvagent, true);
		}
	}
	
	/** 
	 * Method to merge map output transformation metadata.
	 * 
	 * @param values
	 * @return
	 * @throws IOException 
	 */
	@Override
	public void mergeAndOutputTransformationMetadata(Iterator<DistinctValue> values, String outputDir, int colID, JobConf job, TfAgents agents) throws IOException {
		HashMap<String, Long> map = new HashMap<String,Long>();
		
		DistinctValue d = new DistinctValue();
		String word = null;
		Long count = null, val = null;
		while(values.hasNext()) {
			d.reset();
			d = values.next();
			
			word = d.getWord();
			count = d.getCount();
			
			val = map.get(word);
			if(val == null) 
				map.put(word, count);
			else 
				map.put(word, val+count);
		}
		
		writeMetadata(map, outputDir, colID, FileSystem.get(job), agents.getMVImputeAgent(), false);
	}
	
	// ------------------------------------------------------------------------------------------------
	
	public HashMap<Integer, HashMap<String,Long>> getCPRecodeMaps() { return _rcdMaps; }
	
	HashMap<Integer, HashMap<String,String>> _finalMaps = null;
	public HashMap<Integer, HashMap<String,String>> getRecodeMaps() {
		return _finalMaps;
	}
	
	/**
	 * Method to load recode maps of all attributes, at once.
	 * 
	 * @param job
	 * @throws IOException
	 */
	@Override
	public void loadTxMtd(JobConf job, FileSystem fs, Path txMtdDir) throws IOException {
		if ( _rcdList == null )
			return;
		
		_finalMaps = new HashMap<Integer, HashMap<String, String>>();
	
		if(fs.isDirectory(txMtdDir)) {
			for(int i=0; i<_rcdList.length;i++) {
				int colID = _rcdList[i];
				
				Path path = new Path( txMtdDir + "/Recode/" + outputColumnNames[colID-1] + RCD_MAP_FILE_SUFFIX);
				TransformationAgent.checkValidInputFile(fs, path, true); 
				
				HashMap<String,String> map = new HashMap<String,String>();
				
				BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(path)));
				String line = null, word=null;
				String rcdIndex = null;
				
				// Example line to parse: "WN (1)67492",1,61975
				while((line=br.readLine())!=null) {
					
					// last occurrence of quotation mark
					int idxQuote = line.lastIndexOf('"');
					word = UtilFunctions.unquote(line.substring(0,idxQuote+1));
					
					int idx = idxQuote+2;
					while(line.charAt(idx) != TXMTD_SEP.charAt(0))
						idx++;
					rcdIndex = line.substring(idxQuote+2,idx); 
					
					map.put(word, rcdIndex);
				}
				br.close();
				_finalMaps.put(colID, map);
			}
		}
		else {
			fs.close();
			throw new RuntimeException("Path to recode maps must be a directory: " + txMtdDir);
		}
	}
	
	/**
	 * Method to apply transformations.
	 * 
	 * @param words
	 * @return
	 */
	@Override
	public String[] apply(String[] words) {
		if ( _rcdList == null )
			return words;
		
		for(int i=0; i < _rcdList.length; i++) {
			int colID = _rcdList[i];
			try {
				words[colID-1] = _finalMaps.get(colID).get(UtilFunctions.unquote(words[colID-1].trim()));
			} catch(NullPointerException e) {
				System.err.println("Maps for colID="+colID + " may be null (map = " + _finalMaps.get(colID) + ")");
				throw new RuntimeException(e);
			}
		}
			
		return words;
	}
	
	/**
	 * Check if the given column ID is subjected to this transformation.
	 * 
	 */
	public int isRecoded(int colID)
	{
		if(_rcdList == null)
			return -1;
		
		int idx = Arrays.binarySearch(_rcdList, colID);
		return ( idx >= 0 ? idx : -1);
	}

	public String[] cp_apply(String[] words) {
		if ( _rcdList == null )
			return words;
		
		for(int i=0; i < _rcdList.length; i++) {
			int colID = _rcdList[i];
			try {
				words[colID-1] = Long.toString(_rcdMaps.get(colID).get(UtilFunctions.unquote(words[colID-1].trim())));
			} catch(NullPointerException e) {
				if(words[colID-1].isEmpty() && MVImputeAgent.isNA("", TransformationAgent.NAstrings) )
					throw new RuntimeException("Empty string (a missing value) in column ID " + colID + " is not handled. Consider adding an imputation method on this column.");		
				throw new RuntimeException("ColID="+colID + ", word=" + words[colID-1] + ", maps entry not found (map = " + _rcdMaps.get(colID) + ")");
			}
		}
			
		return words;
	}
	
	
	public void printMaps() {
		for(Integer k : _rcdMaps.keySet()) {
			System.out.println("Column " + k);
			HashMap<String,Long> map = _rcdMaps.get(k);
			for(String w : map.keySet()) {
				System.out.println("    " + w + " : " + map.get(w));
			}
		}
	}
	
	public void print() {
		System.out.print("Recoding List: \n    ");
		for(int i : _rcdList) {
			System.out.print(i + " ");
		}
		System.out.println();
	}
}
 
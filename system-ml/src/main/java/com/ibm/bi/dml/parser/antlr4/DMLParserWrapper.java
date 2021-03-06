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

package com.ibm.bi.dml.parser.antlr4;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.DefaultErrorStrategy;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import com.ibm.bi.dml.api.DMLScript;
import com.ibm.bi.dml.conf.ConfigurationManager;
import com.ibm.bi.dml.parser.DMLProgram;
import com.ibm.bi.dml.parser.ForStatement;
import com.ibm.bi.dml.parser.ForStatementBlock;
import com.ibm.bi.dml.parser.FunctionStatementBlock;
import com.ibm.bi.dml.parser.IfStatement;
import com.ibm.bi.dml.parser.IfStatementBlock;
import com.ibm.bi.dml.parser.ImportStatement;
import com.ibm.bi.dml.parser.LanguageException;
import com.ibm.bi.dml.parser.ParForStatement;
import com.ibm.bi.dml.parser.ParForStatementBlock;
import com.ibm.bi.dml.parser.ParseException;
import com.ibm.bi.dml.parser.Statement;
import com.ibm.bi.dml.parser.StatementBlock;
import com.ibm.bi.dml.parser.WhileStatement;
import com.ibm.bi.dml.parser.WhileStatementBlock;
import com.ibm.bi.dml.parser.antlr4.DmlParser.DmlprogramContext;
import com.ibm.bi.dml.parser.antlr4.DmlParser.FunctionStatementContext;
import com.ibm.bi.dml.parser.antlr4.DmlParser.StatementContext;
import com.ibm.bi.dml.parser.antlr4.DmlSyntacticErrorListener.CustomDmlErrorListener;
import com.ibm.bi.dml.runtime.util.LocalFileUtils;

/**
 * This is the main entry point for the Antlr4 parser.
 * Dml.g4 is the grammar file which enforces syntactic structure of DML program. 
 * DmlSyntaticValidator on other hand captures little bit of semantic as well as does the job of translation of Antlr AST to DMLProgram.
 * At a high-level, DmlSyntaticValidator implements call-back methods that are called by walker.walk(validator, tree)
 * The callback methods are of two type: enterSomeASTNode() and exitSomeASTNode()
 * It is important to note that almost every node in AST has either ExpressionInfo or StatementInfo object associated with it.
 * The key design decision is that while "exiting" the node (i.e. callback to exitSomeASTNode), we use information in given AST node and construct an object of type Statement or Expression and put it in StatementInfo or ExpressionInfo respectively. 
 * This way it avoids any bugs due to lookahead and one only has to "think as an AST node", thereby making any changes to parse code much simpler :)
 * 
 * Note: to add additional builtin function, one only needs to modify DmlSyntaticValidator (which is java file and provides full Eclipse tooling support) not g4. 
 * 
 * To separate logic of semantic validation, DmlSyntaticValidatorHelper contains functions that do semantic validation. Currently, there is no semantic validation as most of it is delegated to subsequent validation phase. 
 * 
 * Whenever there is a parse error, it goes through DmlSyntacticErrorListener. This allows us to pipe the error messages to any future pipeline as well as control the format in an elegant manner. 
 * There are three types of messages passed:
 * - Syntactic errors: When passed DML script doesnot conform to syntatic structure enforced by Dml.g4
 * - Validation errors: Errors due to translation of AST to  DMLProgram
 * - Validation warnings: Messages to inform users that there might be potential bug in their program
 * 
 * As of this moment, Antlr4ParserWrapper is stateful and cannot be multithreaded. This is not big deal because each users calls SystemML in different process.
 * If in future we intend to make it multi-threaded, look at cleanUpState method and resolve the dependency accordingly.    
 *
 */
public class DMLParserWrapper {
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
			"US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";

	private static final Log LOG = LogFactory.getLog(DMLScript.class.getName());
	
	/**
	 * Custom wrapper to convert statement into statement blocks. Called by doParse and in DmlSyntacticValidator for for, parfor, while, ...
	 * @param current a statement
	 * @return corresponding statement block
	 */
	public static StatementBlock getStatementBlock(Statement current) {
		StatementBlock blk = null;
		if(current instanceof ParForStatement) {
			blk = new ParForStatementBlock();
			blk.addStatement(current);
		}
		else if(current instanceof ForStatement) {
			blk = new ForStatementBlock();
			blk.addStatement(current);
		}
		else if(current instanceof IfStatement) {
			blk = new IfStatementBlock();
			blk.addStatement(current);
		}
		else if(current instanceof WhileStatement) {
			blk = new WhileStatementBlock();
			blk.addStatement(current);
		}
		else {
			// This includes ImportStatement
			blk = new StatementBlock();
			blk.addStatement(current);
		}
		return blk;
	}

	/**
	 * Parses the passed file with command line parameters. You can either pass both (local file) or just dmlScript (hdfs) or just file name (import command)
	 * @param fileName either full path or null --> only used for better error handling
	 * @param dmlScript required
	 * @param argVals
	 * @return
	 * @throws ParseException
	 */
	public DMLProgram parse(String fileName, String dmlScript, HashMap<String,String> argVals) throws ParseException {
		DMLProgram prog = null;
		
		if(dmlScript == null || dmlScript.trim().compareTo("") == 0) {
			throw new ParseException("Incorrect usage of parse. Please pass dmlScript not just filename");
		}
		
		// Set the pipeline required for ANTLR parsing
		DMLParserWrapper parser = new DMLParserWrapper();
		prog = parser.doParse(fileName, dmlScript, argVals);
		
		if(prog == null) {
			throw new ParseException("One or more errors found during parsing (could not construct AST for file: " + fileName + "). Cannot proceed ahead.");
		}
		return prog;
	}

	/**
	 * This function is supposed to be called directly only from DmlSyntacticValidator when it encounters 'import'
	 * @param fileName
	 * @return null if atleast one error
	 */
	public DMLProgram doParse(String fileName, String dmlScript, HashMap<String,String> argVals) throws ParseException {
		DMLProgram dmlPgm = null;
		
		org.antlr.v4.runtime.ANTLRInputStream in;
		try {
			if(dmlScript == null) {
				dmlScript = readDMLScript(fileName);
			}
			
			InputStream stream = new ByteArrayInputStream(dmlScript.getBytes());
			in = new org.antlr.v4.runtime.ANTLRInputStream(stream);
//			else {
//				if(!(new File(fileName)).exists()) {
//					throw new ParseException("ERROR: Cannot open file:" + fileName);
//				}
//				in = new org.antlr.v4.runtime.ANTLRInputStream(new java.io.FileInputStream(fileName));
//			}
		} catch (FileNotFoundException e) {
			throw new ParseException("ERROR: Cannot find file:" + fileName);
		} catch (IOException e) {
			throw new ParseException("ERROR: Cannot open file:" + fileName);
		} catch (LanguageException e) {
			throw new ParseException("ERROR: " + e.getMessage());
		}

		DmlprogramContext ast = null;
		CustomDmlErrorListener errorListener = new CustomDmlErrorListener();
		
		try {
			DmlLexer lexer = new DmlLexer(in);
			org.antlr.v4.runtime.CommonTokenStream tokens = new org.antlr.v4.runtime.CommonTokenStream(lexer);
			DmlParser antlr4Parser = new DmlParser(tokens);
			
			boolean tryOptimizedParsing = false; // For now no optimization, since it is not able to parse integer value. 
	
			if(tryOptimizedParsing) {
				// Try faster and simpler SLL
				antlr4Parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
				antlr4Parser.removeErrorListeners();
				antlr4Parser.setErrorHandler(new BailErrorStrategy());
				try{
					ast = antlr4Parser.dmlprogram();
					// If successful, no need to try out full LL(*) ... SLL was enough
				}
				catch(ParseCancellationException ex) {
					// Error occurred, so now try full LL(*) for better error messages
					tokens.reset();
					antlr4Parser.reset();
					if(fileName != null) {
						errorListener.pushCurrentFileName(fileName);
						// DmlSyntacticErrorListener.currentFileName.push(fileName);
					}
					else {
						errorListener.pushCurrentFileName("MAIN_SCRIPT");
						// DmlSyntacticErrorListener.currentFileName.push("MAIN_SCRIPT");
					}
					// Set our custom error listener
					antlr4Parser.addErrorListener(errorListener);
					antlr4Parser.setErrorHandler(new DefaultErrorStrategy());
					antlr4Parser.getInterpreter().setPredictionMode(PredictionMode.LL);
					ast = antlr4Parser.dmlprogram();
				}
			}
			else {
				// Set our custom error listener
				antlr4Parser.removeErrorListeners();
				antlr4Parser.addErrorListener(errorListener);
				errorListener.pushCurrentFileName(fileName);
	
				// Now do the parsing
				ast = antlr4Parser.dmlprogram();
			}
		}
		catch(Exception e) {
			throw new ParseException("ERROR: Cannot parse the program:" + fileName);
		}
		

		try {
			// Now convert the parse tree into DMLProgram
			// Do syntactic validation while converting 
			org.antlr.v4.runtime.tree.ParseTree tree = ast;
			// And also do syntactic validation
			org.antlr.v4.runtime.tree.ParseTreeWalker walker = new ParseTreeWalker();
			DmlSyntacticValidatorHelper helper = new DmlSyntacticValidatorHelper(errorListener);
			DmlSyntacticValidator validator = new DmlSyntacticValidator(helper, errorListener.peekFileName(), argVals);
			walker.walk(validator, tree);
			errorListener.popFileName();
			if(errorListener.isAtleastOneError()) {
				return null;
			}
			dmlPgm = createDMLProgram(ast);
		}
		catch(Exception e) {
			throw new ParseException("ERROR: Cannot translate the parse tree into DMLProgram:" + e.getMessage());
		}
		
		return dmlPgm;
	}

	private DMLProgram createDMLProgram(DmlprogramContext ast) {

		DMLProgram dmlPgm = new DMLProgram();

		// First add all the functions
		for(FunctionStatementContext fn : ast.functionBlocks) {
			FunctionStatementBlock functionStmtBlk = new FunctionStatementBlock();
			functionStmtBlk.addStatement(fn.info.stmt);
			try {
				// TODO: currently the logic of nested namespace is not clear.
				String namespace = DMLProgram.DEFAULT_NAMESPACE;
				dmlPgm.addFunctionStatementBlock(namespace, fn.info.functionName, functionStmtBlk);
			} catch (LanguageException e) {
				LOG.error("line: " + fn.start.getLine() + ":" + fn.start.getCharPositionInLine() + " cannot process the function " + fn.info.functionName);
				return null;
			}
		}

		// Then add all the statements
		for(StatementContext stmtCtx : ast.blocks) {
			com.ibm.bi.dml.parser.Statement current = stmtCtx.info.stmt;
			if(current == null) {
				LOG.error("line: " + stmtCtx.start.getLine() + ":" + stmtCtx.start.getCharPositionInLine() + " cannot process the statement");
				return null;
			}

			if(current instanceof ImportStatement) {
				// Handle import statements separately
				if(stmtCtx.info.namespaces != null) {
					// Add the DMLProgram entries into current program
					for(Map.Entry<String, DMLProgram> entry : stmtCtx.info.namespaces.entrySet()) {
						dmlPgm.getNamespaces().put(entry.getKey(), entry.getValue());
						
//						// Don't add DMLProgram into the current program, just add function statements
						// dmlPgm.getNamespaces().put(entry.getKey(), entry.getValue());
						// Add function statements to current dml program
//						DMLProgram importedPgm = entry.getValue();
//						try {
//							for(FunctionStatementBlock importedFnBlk : importedPgm.getFunctionStatementBlocks()) {
//								if(importedFnBlk.getStatements() != null && importedFnBlk.getStatements().size() == 1) {
//									String functionName = ((FunctionStatement)importedFnBlk.getStatement(0)).getName();
//									System.out.println("Adding function => " + entry.getKey() + "::" + functionName);
//									TODO:33
//									dmlPgm.addFunctionStatementBlock(entry.getKey(), functionName, importedFnBlk);
//								}
//								else {
//									LOG.error("line: " + stmtCtx.start.getLine() + ":" + stmtCtx.start.getCharPositionInLine() + " incorrect number of functions in the imported function block .... strange");
//									return null;
//								}
//							}
//							if(importedPgm.getStatementBlocks() != null && importedPgm.getStatementBlocks().size() > 0) {
//								LOG.warn("Only the functions can be imported from the namespace " + entry.getKey());
//							}
//						} catch (LanguageException e) {
//							LOG.error("line: " + stmtCtx.start.getLine() + ":" + stmtCtx.start.getCharPositionInLine() + " cannot import functions from the file in the import statement: " + e.getMessage());
//							return null;
//						}
					}
				}
				else {
					LOG.error("line: " + stmtCtx.start.getLine() + ":" + stmtCtx.start.getCharPositionInLine() + " cannot process the import statement");
					return null;
				}
			}

			// Now wrap statement into individual statement block
			// merge statement will take care of merging these blocks
			dmlPgm.addStatementBlock(getStatementBlock(current));
		}

		dmlPgm.mergeStatementBlocks();
		return dmlPgm;
	}
	
	public static String readDMLScript( String script ) 
			throws IOException, LanguageException
	{
		String dmlScriptStr = null;
		
		//read DML script from file
		if(script == null)
			throw new LanguageException("DML script path was not specified!");
		
		StringBuilder sb = new StringBuilder();
		BufferedReader in = null;
		try 
		{
			//read from hdfs or gpfs file system
			if(    script.startsWith("hdfs:") 
				|| script.startsWith("gpfs:") ) 
			{ 
				if( !LocalFileUtils.validateExternalFilename(script, true) )
					throw new LanguageException("Invalid (non-trustworthy) hdfs filename.");
				FileSystem fs = FileSystem.get(ConfigurationManager.getCachedJobConf());
				Path scriptPath = new Path(script);
				in = new BufferedReader(new InputStreamReader(fs.open(scriptPath)));
			}
			// from local file system
			else 
			{ 
				if( !LocalFileUtils.validateExternalFilename(script, false) )
					throw new LanguageException("Invalid (non-trustworthy) local filename.");
				in = new BufferedReader(new FileReader(script));
			}
			
			//core script reading
			String tmp = null;
			while ((tmp = in.readLine()) != null)
			{
				sb.append( tmp );
				sb.append( "\n" );
			}
		}
		catch (IOException ex)
		{
			LOG.error("Failed to read the script from the file system", ex);
			throw ex;
		}
		finally 
		{
			if( in != null )
				in.close();
		}
		
		dmlScriptStr = sb.toString();
		
		return dmlScriptStr;
	}
}

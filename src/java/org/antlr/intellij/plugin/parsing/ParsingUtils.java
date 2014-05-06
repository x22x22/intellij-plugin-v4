package org.antlr.intellij.plugin.parsing;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.antlr.intellij.adaptor.parser.SyntaxError;
import org.antlr.intellij.adaptor.parser.SyntaxErrorListener;
import org.antlr.intellij.plugin.ANTLRv4PluginController;
import org.antlr.intellij.plugin.PluginIgnoreMissingTokensFileErrorManager;
import org.antlr.intellij.plugin.parser.ANTLRv4Lexer;
import org.antlr.intellij.plugin.parser.ANTLRv4Parser;
import org.antlr.intellij.plugin.preview.PreviewState;
import org.antlr.v4.Tool;
import org.antlr.v4.parse.ANTLRParser;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.LexerNoViableAltException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenFactory;
import org.antlr.v4.runtime.TokenSource;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.misc.Triple;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.GrammarRootAST;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ParsingUtils {
	public static Grammar BAD_PARSER_GRAMMAR;
	public static LexerGrammar BAD_LEXER_GRAMMAR;

	public static Token nextRealToken(CommonTokenStream tokens, int i) {
		int n = tokens.size();
		i++; // search after current i token
		if ( i>=n || i<0 ) return null;
		Token t = tokens.get(i);
		while ( t.getChannel()==Token.HIDDEN_CHANNEL ) {
			if ( t.getType()==Token.EOF ) {
				TokenSource tokenSource = tokens.getTokenSource();
				if ( tokenSource==null ) {
					return new CommonToken(Token.EOF, "EOF");
				}
				TokenFactory<?> tokenFactory = tokenSource.getTokenFactory();
				if ( tokenFactory==null ) {
					return new CommonToken(Token.EOF, "EOF");
				}
				return tokenFactory.create(Token.EOF, "EOF");
			}
			i++;
			if ( i>=n ) return null; // just in case no EOF
			t = tokens.get(i);
		}
		return t;
	}

	public static Token previousRealToken(CommonTokenStream tokens, int i) {
		int size = tokens.size();
		i--; // search before current i token
		if ( i>=size || i<0 ) return null;
		Token t = tokens.get(i);
		while ( t.getChannel()==Token.HIDDEN_CHANNEL ) {
			i--;
			if ( i<0 ) return null;
			t = tokens.get(i);
		}
		return t;
	}

	public static Token getTokenUnderCursor(CommonTokenStream tokens, int offset) {
		Token tokenUnderCursor = null;
		for (Token t : tokens.getTokens()) {
			int begin = t.getStartIndex();
			int end = t.getStopIndex()+1;
//				System.out.println("test "+t+" for "+offset);
			if ( offset >= begin && offset < end ) {
				tokenUnderCursor = t;
				break;
			}
		}
		return tokenUnderCursor;
	}

	public static SyntaxError getErrorUnderCursor(java.util.List<SyntaxError> errors, int offset) {
		for (SyntaxError e : errors) {
			int a, b;
			RecognitionException cause = e.getException();
			if ( cause instanceof LexerNoViableAltException) {
				a = ((LexerNoViableAltException) cause).getStartIndex();
				b = ((LexerNoViableAltException) cause).getStartIndex()+1;
			}
			else {
				Token offendingToken = (Token)e.getOffendingSymbol();
				a = offendingToken.getStartIndex();
				b = offendingToken.getStopIndex()+1;
			}
			if ( offset >= a && offset < b ) { // cursor is over some kind of error
				return e;
			}
		}
		return null;
	}

	public static CommonTokenStream tokenizeANTLRGrammar(String text) {
		ANTLRInputStream input = new ANTLRInputStream(text);
		ANTLRv4Lexer lexer = new ANTLRv4Lexer(input);
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		tokens.fill();
		return tokens;
	}

	public static Triple<Parser, ParseTree, SyntaxErrorListener> parseANTLRGrammar(String text) {
		ANTLRInputStream input = new ANTLRInputStream(text);
		ANTLRv4Lexer lexer = new ANTLRv4Lexer(input);
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		ANTLRv4Parser parser = new ANTLRv4Parser(tokens);

		SyntaxErrorListener listener = new SyntaxErrorListener();
		parser.removeErrorListeners();
		parser.addErrorListener(listener);
		lexer.removeErrorListeners();
		lexer.addErrorListener(listener);

		ParseTree t = parser.grammarSpec();
		return new Triple<Parser, ParseTree, SyntaxErrorListener>(parser, t, listener);
	}

	public static Triple<MyParser, ParseTree, SyntaxErrorListener> parseText(PreviewState previewState,
																			 final VirtualFile grammarFile,
																			 String inputText)
		throws IOException
	{
		// TODO:Try to reuse the same parser and lexer.
		String grammarFileName = grammarFile.getPath();
		if (!new File(grammarFileName).exists()) {
			ANTLRv4PluginController.LOG.error("parseText grammar doesn't exit " + grammarFileName);
			return null;
		}

		if ( previewState.g == BAD_PARSER_GRAMMAR ||
			previewState.lg == BAD_LEXER_GRAMMAR)
		{
			return null;
		}

		ANTLRInputStream input = new ANTLRInputStream(inputText);
		LexerInterpreter lexEngine;
		lexEngine = previewState.lg.createLexerInterpreter(input);
//		lexEngine.setTokenFactory(
//			new CommonTokenFactory() {
//				@Override
//				public CommonToken create(Pair<TokenSource, CharStream> source, int type, String text, int channel, int start, int stop, int line, int charPositionInLine) {
//					CommonToken t = new CommonToken(source, type, channel, start, stop);
//					t.setLine(line);
//					t.setCharPositionInLine(charPositionInLine);
//					if ( text!=null ) {
//						t.setText(text);
//					}
//					else if ( copyText && source.b != null ) {
//						t.setText(source.b.getText(Interval.of(start,stop)));
//					}
//
//					return t;
//				}
//
//				@Override
//				public CommonToken create(int type, String text) {
//					return new CommonToken(type, text);
//				}
//			});

		CommonTokenStream tokens = new CommonTokenStream(lexEngine);
		MyParser parser = new MyParser(previewState, tokens);

		SyntaxErrorListener syntaxErrorListener = new SyntaxErrorListener();
		parser.removeErrorListeners();
		parser.addErrorListener(syntaxErrorListener);
		lexEngine.removeErrorListeners();
		lexEngine.addErrorListener(syntaxErrorListener);

		Rule start = previewState.g.getRule(previewState.startRuleName);
		if ( start==null ) {
			return null; // can't find start rule
		}
		ParseTree t = parser.parse(start.index);

		if ( t!=null ) {
			return new Triple<MyParser, ParseTree, SyntaxErrorListener>(parser, t, syntaxErrorListener);
		}
		return null;
	}

	/** Get lexer and parser grammars */
	public static Grammar[] loadGrammars(String grammarFileName, Project project) {
		ANTLRv4PluginController.LOG.info("loadGrammars open "+grammarFileName+" "+project.getName());
		Tool antlr = new PluginANTLRTool();

		antlr.errMgr = new PluginIgnoreMissingTokensFileErrorManager(antlr);
		antlr.errMgr.setFormat("antlr");
		MyANTLRToolListener listener = new MyANTLRToolListener(antlr);
		antlr.addListener(listener);

		String combinedGrammarFileName = null;
		String lexerGrammarFileName = null;
		String parserGrammarFileName = null;

		// basically here I am importing the loadGrammar() method from Tool
		// so that I can check for an empty AST coming back.
		GrammarRootAST grammarRootAST = antlr.parseGrammar(grammarFileName);
		if ( grammarRootAST==null ) {
			ANTLRv4PluginController.LOG.info("Empty or bad grammar "+grammarFileName+" "+project.getName());
			return null;
		}
		Grammar g = antlr.createGrammar(grammarRootAST);
		g.fileName = grammarFileName;
		antlr.process(g, false);

		// examine's Grammar AST from v4 itself;
		// hence use ANTLRParser.X not ANTLRv4Parser from this plugin
		switch ( g.getType() ) {
			case ANTLRParser.PARSER :
				parserGrammarFileName = grammarFileName;
				int i = grammarFileName.indexOf("Parser");
				if ( i>=0 ) {
					lexerGrammarFileName = grammarFileName.substring(0, i) + "Lexer.g4";
				}
				break;
			case ANTLRParser.LEXER :
				lexerGrammarFileName = grammarFileName;
				int i2 = grammarFileName.indexOf("Lexer");
				if ( i2>=0 ) {
					parserGrammarFileName = grammarFileName.substring(0, i2) + "Parser.g4";
				}
				break;
			case ANTLRParser.COMBINED :
				combinedGrammarFileName = grammarFileName;
				lexerGrammarFileName = grammarFileName+"Lexer";
				parserGrammarFileName = grammarFileName+"Parser";
				break;
		}

		if ( lexerGrammarFileName==null ) {
			ANTLRv4PluginController.LOG.error("Can't compute lexer file name from "+grammarFileName, (Throwable)null);
			return null;
		}
		if ( parserGrammarFileName==null ) {
			ANTLRv4PluginController.LOG.error("Can't compute parser file name from "+grammarFileName, (Throwable)null);
			return null;
		}

		LexerGrammar lg = null;

		if ( combinedGrammarFileName!=null ) {
			// already loaded above
			lg = g.getImplicitLexer();
			if ( listener.grammarErrorMessage!=null ) {
				g = null;
			}
		}
		else {
			try {
				lg = (LexerGrammar)Grammar.load(lexerGrammarFileName);
			}
			catch (ClassCastException cce) {
				ANTLRv4PluginController.LOG.error("File " + lexerGrammarFileName + " isn't a lexer grammar", cce);
				lg = null;
			}
			if ( listener.grammarErrorMessage!=null ) {
				lg = null;
			}
			g = loadGrammar(antlr, parserGrammarFileName, lg);
		}

		if ( g==null ) {
			ANTLRv4PluginController.LOG.info("loadGrammars parser "+parserGrammarFileName+" has errors");
			g = BAD_PARSER_GRAMMAR;
		}
		if ( lg==null ) {
			ANTLRv4PluginController.LOG.info("loadGrammars lexer "+lexerGrammarFileName+" has errors");
			lg = BAD_LEXER_GRAMMAR;
		}
		ANTLRv4PluginController.LOG.info("loadGrammars "+lg.getRecognizerName()+", "+g.getRecognizerName());
		return new Grammar[] {lg, g};
	}

	/** Same as loadGrammar(fileName) except import vocab from existing lexer */
	public static Grammar loadGrammar(Tool tool, String fileName, LexerGrammar lexerGrammar) {
		GrammarRootAST grammarRootAST = tool.parseGrammar(fileName);
		final Grammar g = tool.createGrammar(grammarRootAST);
		g.fileName = fileName;
		g.importVocab(lexerGrammar);
		tool.process(g, false);
		return g;
	}

	public static Map<Integer, org.antlr.runtime.CommonToken> getStateToGrammarRegionMap(GrammarRootAST ast) {
		Map<Integer, org.antlr.runtime.CommonToken> stateToGrammarRegionMap =
			new HashMap<Integer, org.antlr.runtime.CommonToken>();

		if ( ast==null ) return stateToGrammarRegionMap;

		IntervalSet terminalTokenTypes = new IntervalSet();
		terminalTokenTypes.add(ANTLRParser.STRING_LITERAL);
		terminalTokenTypes.add(ANTLRParser.TOKEN_REF);
		terminalTokenTypes.add(ANTLRParser.SET);
		List<GrammarAST> terminalNodes = ast.getNodesWithType(terminalTokenTypes);
		for (GrammarAST n : terminalNodes) {
			org.antlr.runtime.CommonToken t = (org.antlr.runtime.CommonToken)n.getToken();
			if (n.atnState != null) {
				stateToGrammarRegionMap.put(n.atnState.stateNumber, t);
			}
			else {
				if ( n.getParent().getType() != ANTLRParser.SET ) {
					ANTLRv4PluginController.LOG.error("null ATN state for node " + n + " in " + ast.getGrammarName());
				}
			}
		}

		return stateToGrammarRegionMap;
	}
}

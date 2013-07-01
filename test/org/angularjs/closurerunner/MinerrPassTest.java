package org.angularjs.closurerunner;

import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.CompilerPass;
import com.google.javascript.jscomp.CompilerTestCase;
import com.google.javascript.jscomp.JsAst;
import com.google.javascript.jscomp.SourceFile;

import com.google.javascript.rhino.Node;

import org.json.JSONObject;

import junit.framework.TestCase;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * Tests {@link MinerrPass}.
 */
public class MinerrPassTest extends CompilerTestCase {

  private ByteArrayOutputStream dummyOutput;
  private String subCode;

  public MinerrPassTest() {
    super();
    enableLineNumberCheck(false);
    dummyOutput = new ByteArrayOutputStream();
    subCode = null;
  }

  public void setUp() throws Exception {
    super.setUp();
    dummyOutput.reset();
    subCode = null;
  }

  @Override
  public CompilerPass getProcessor(Compiler compiler) {
    return new MinerrPass(compiler, new PrintStream(dummyOutput), subCode);
  }

  @Override
  public int getNumRepetitions() {
    // This pass only runs once.
    return 1;
  }

  public void assertExtracted(String expectJson) throws Exception {
    JSONObject json = new JSONObject(expectJson);
    assertEquals(dummyOutput.toString(), json.toString());
  }

  public void testMinerrRemovesDescriptiveNameAndExtractsErrorInfo() throws Exception {
    test("testMinErr('test1', 'This is a {0}', test);",
      "testMinErr('test1', test);");
    assertExtracted("{'test':{'test1':'This is a {0}'}}");
  }

  public void testMinerrExtractsMultipleErrorMessagesFromOneNamespace() throws Exception {
    test("testMinErr('test1', 'This is a {0}', test);\n"
      + "minErr('test')('test2', 'The answer is {0}', 42);",
      "testMinErr('test1', test);\n"
      + "minErr('test')('test2', 42);");
    assertExtracted("{'test':{'test1':'This is a {0}','test2':'The answer is {0}'}}");
  }

  public void testMinerrExtractsMultipleErrorMessagesFromMultipleNamespaces() throws Exception {
    test("fooMinErr('one', 'Too many {0}', 'hippies');\n"
      + "barMinErr('one', 'Not enough {0}', 'mojo');\n"
      + "fooMinErr('three', 'The answer is {0}', 42);",
      "fooMinErr('one', 'hippies');\n"
      + "barMinErr('one', 'mojo');\n"
      + "fooMinErr('three', 42);");
    assertExtracted("{'foo':{'one':'Too many {0}','three':'The answer is {0}'},"
      + "'bar':{'one':'Not enough {0}'}}");
  }

  public void testMinerrShouldNotTransformNonMinerrErrors() {
    testSame("throw new Error(testMinErr('test1', 'This is a {0}', test));",
      MinerrPass.THROW_IS_NOT_MINERR_ERROR_WARNING);
  }

  public void testMinerrPassShouldNotModifyCodeThatDoesNotUseMinerr() {
    testSame("for (var i = 0; i < baz; i++) { console.log('Hi there!'); }\n"
      + "42 - foo;");
  }

  public void testMinerrPassShouldNotModifyCodeThatDoesNotCallMinerr() {
    testSame("(function () {\n"
      + "var fooMinErr = minErr('foo');\n"
      + "return fooMinErr; })();");
  }

  public void testMinerrPassExtractsErrorsFromNestedCallExprs() throws Exception {
    test("function doIt (testMinErr) {\n"
      +  "  (function (foo) {\n"
      +  "    testMinErr('nest', 'This {0} should be extracted', foo);\n"
      +  "  })('test'); }",
      "function doIt (testMinErr) {\n"
      + "(function (foo) {\n"
      + "  testMinErr('nest', foo);\n"
      + "})('test'); }");
    assertExtracted("{'test':{'nest':'This {0} should be extracted'}}");
  }

  public void testMinerrPassShouldHandleConcatenatedErrorMessageStrings() throws Exception {
    test("testMinErr('test', 'This is' + ' a very long ' + 'string.');",
      "testMinErr('test');");
    assertExtracted("{'test':{'test':'This is a very long string.'}}");
  }

  public void testMinerrPassShouldHandleConcatenatedErrorCodeStrings() throws Exception {
    test("testMinErr('test' + 'foo', 'This is a {0}', test);",
      "testMinErr('test' + 'foo', test);");
    assertExtracted("{'test':{'testfoo':'This is a {0}'}}");
  }

  public void testMinerrPassShouldThrowErrorForVariableTemplateString() {
    test("(function (foo) {\n"
        +"  testMinErr('test', foo, 42);\n"
        +"})('O{0}ps!');", null, MinerrPass.UNSUPPORTED_STRING_EXPRESSION_ERROR);
  }

  public void testMinerrPassShouldThrowErrorForVariableErrorCode() {
    test("(function (foo) {\n"
        +"  testMinErr(foo, 'The answer is {0}', 42);\n"
        +"})('oops');", null, MinerrPass.UNSUPPORTED_STRING_EXPRESSION_ERROR);
  }

  public void testMinerrPassShouldSubstituteTheMinerrDefinition() {
    subCode = 
      "function minErr(module) {\n" +
      "  return module + 42; }";
    test("function minErr(module) {\n"
        +"  console.log('This should be ripped out.'); }",
        "function minErr(module) {\n"
        +" return module + 42; }");
  }

  public void testMinerrPassSubstitutionPreservesRegularExpressions() {
    subCode = 
      "function minErr(module) {\n" +
      "return new RegExp(module + '\\\\d+'); }";
    test("function minErr(module) {\n"
        +"  console.log('This should be ripped out.'); }",
        "function minErr(module) {\n"
        +" return new RegExp(module + '\\\\d+'); }");
  }

  public void testMinerrPassWarnsIfMultipleMinerrDefinitionsExist() {
    testSame("(function () {\n"
        +"  function minErr(module) {\n"
        +"    return module + 42; }\n"
        +"})();\n"
        +"(function () {\n"
        +"  function minErr(module) {\n"
        +"    return module + 9001; }\n"
        +"})();", MinerrPass.MULTIPLE_MINERR_DEFINITION_WARNING);
  }

}
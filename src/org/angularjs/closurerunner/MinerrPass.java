package org.angularjs.closurerunner;

import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.CompilerPass;
import com.google.javascript.jscomp.DiagnosticType;
import com.google.javascript.jscomp.JsAst;
import com.google.javascript.jscomp.JSError;
import com.google.javascript.jscomp.NodeTraversal;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.SourceFile;

import com.google.javascript.rhino.Node;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.io.PrintStream;

class MinerrPass extends AbstractPostOrderCallback implements CompilerPass {
  final AbstractCompiler compiler;
  final Pattern minerrInstanceRegex = Pattern.compile("^(\\S+)MinErr$");
  private Map<String, Map<String, String>> namespaces;
  private Map<String, String> globalNamespace;
  private List<Node> minerrInstances;
  private PrintStream errorConfigOutput;
  private Node minerrDefNode;
  private String minerrDefSource;

  static final DiagnosticType THROW_IS_NOT_MINERR_ERROR_WARNING =
      DiagnosticType.warning("JSC_THROW_IS_NOT_MINERR_ERROR_WARNING",
          "Throw expression is not a minErr instance.");

  static final DiagnosticType UNSUPPORTED_STRING_EXPRESSION_ERROR =
      DiagnosticType.error("JSC_UNSUPPORTED_STRING_EXPRESSION_ERROR",
          "Can't extract a static string value where one was expected.");

  static final DiagnosticType MULTIPLE_MINERR_DEFINITION_WARNING =
      DiagnosticType.warning("JSC_MULTIPLE_MINERR_DEFINITION_WARNING",
          "Found definitions for the function 'minErr' in multiple locations.");

  public MinerrPass(AbstractCompiler compiler, PrintStream errorConfigOutput, String minerrDef) {
    this.compiler = compiler;
    namespaces = new HashMap<String, Map<String, String>>();
    globalNamespace = new HashMap<String, String>();
    minerrInstances = new ArrayList<Node>();
    this.errorConfigOutput = errorConfigOutput;
    minerrDefSource = minerrDef;
  }

  public MinerrPass(AbstractCompiler compiler, PrintStream errorConfigOutput) {
    this(compiler, errorConfigOutput, null);
  }

  static String substituteInCode(String code, String url, String separator) {
    return code
            .replace("MINERR_URL", url)
            .replace("MINERR_SEPARATOR", separator);
  }

  private Node createSubstituteMinerrDefinition() {
    SourceFile source = SourceFile.fromCode("MINERR_ASSET", minerrDefSource);
    JsAst ast = new JsAst(source);
    return ast.getAstRoot(compiler).getFirstChild().detachFromParent();
  }

  private boolean isMinerrCall(Node ast) {
    if (ast.isCall()) {
      Node nameNode = ast.getFirstChild();
      if (nameNode.isName()) {
        String name = nameNode.getString();
        return name.equals("minErr");
      }
    }
    return false;
  }

  private boolean isMinerrInstance(Node ast) {
    if (ast.isCall()) {
      Node child = ast.getFirstChild();
      if (child.isName()) {
        String name = child.getString();
        return minerrInstanceRegex.matcher(name).matches();
      }
      return isMinerrCall(child);
    }
    return false;
  }

  private boolean isMinerrDefinition(Node ast) {
    // Only true for functions of the form
    // function minErr(module) { ... }
    // NodeUtil.getNearestFunctionName gives too many false positives.
    if (ast.isFunction()) {
      Node child = ast.getFirstChild();
      if (child.isName()) {
        String name = child.getString();
        return name.equals("minErr");
      }
    }
    return false;
  }

  private <T> List<T> listFromIterable(Iterable<T> iterable) {
    List<T> ts = new ArrayList<T>();
    for (T elt : iterable) {
      ts.add(elt);
    }
    return ts;
  }

  private String getNamespace(Node ast) {
    Node child = ast.getFirstChild();
    if (isMinerrCall(child)) {
      List<Node> grandchildren = listFromIterable(child.children());
      if (grandchildren.size() >= 2) {
        return getExprString(grandchildren.get(1));
      }
      return null;
    }
    if (isMinerrInstance(ast)) {
      String name = child.getString();
      Matcher match = minerrInstanceRegex.matcher(name);
      match.find();
      return match.group(1);
    }
    throw new IllegalArgumentException("Node must be a minErr instance");
  }

  private void addMessageToNamespace(String namespace, String code, String message) {
    if (!namespaces.containsKey(namespace)) {
      namespaces.put(namespace, new HashMap<String, String>());
    }
    Map<String, String> namespacedMessages = namespaces.get(namespace);
    namespacedMessages.put(code, message);
  }

  private void addMessageToGlobalNamespace(String code, String message) {
    globalNamespace.put(code, message);
  }

  private void unmarkInstancesBelow(Node ast) {
    minerrInstances.remove(ast);
    for (Node child : ast.children()) {
      unmarkInstancesBelow(child);
    }
  }

  private String getExprStringR(Node ast) {
    if (ast.isString()) {
      return ast.getString();
    }
    if (ast.isAdd()) {
      return getExprStringR(ast.getFirstChild()) + getExprStringR(ast.getChildAtIndex(1));
    }
    throw new IllegalArgumentException("Wrong node type");
  }

  private String getExprString(Node ast) {
    try {
      return getExprStringR(ast);
    } catch (IllegalArgumentException e) {
      compiler.report(JSError.make(ast, UNSUPPORTED_STRING_EXPRESSION_ERROR));
    }
    return null;
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverse(compiler, root, this);
    boolean codeChanged = false;
    PrintStream stream;

    for (Node instance : minerrInstances) {
      Node templateNode = instance.getChildAtIndex(2);
      Node errCodeNode = instance.getChildAtIndex(1);
      String namespace = getNamespace(instance);

      if (namespace != null) {
        addMessageToNamespace(namespace, getExprString(errCodeNode), getExprString(templateNode));
      } else {
        addMessageToGlobalNamespace(getExprString(errCodeNode), getExprString(templateNode));
      }

      instance.removeChild(templateNode);
      codeChanged = true;
    }

    if (minerrDefNode != null && minerrDefSource != null) {
      Node newMinErrDef = createSubstituteMinerrDefinition();
      newMinErrDef.useSourceInfoFromForTree(minerrDefNode);
      minerrDefNode.getParent().replaceChild(minerrDefNode, newMinErrDef);
      codeChanged = true;
    }

    Map<String, Object> jsonBuilder = new HashMap<String, Object>(namespaces);
    jsonBuilder.putAll(globalNamespace);
    JSONObject json = new JSONObject(jsonBuilder);
    errorConfigOutput.print(json.toString());

    if (codeChanged) {
      compiler.reportCodeChange();
    }
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    if (n.isThrow()) {
      if (!isMinerrInstance(n.getFirstChild())) {
        compiler.report(t.makeError(n, THROW_IS_NOT_MINERR_ERROR_WARNING));
        unmarkInstancesBelow(n);
      }
    }
    if (isMinerrInstance(n)) {
      minerrInstances.add(n);
    }
    if (isMinerrDefinition(n)) {
      if (minerrDefNode == null) {
        minerrDefNode = n;
      } else {
        compiler.report(t.makeError(n, MULTIPLE_MINERR_DEFINITION_WARNING));
      }
    }
  }
}
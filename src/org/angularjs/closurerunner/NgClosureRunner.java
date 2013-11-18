package org.angularjs.closurerunner;

import com.google.common.collect.ArrayListMultimap;

import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CommandLineRunner;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.CompilerPass;
import com.google.javascript.jscomp.CustomPassExecutionTime;

import java.io.InputStream;
import java.io.IOException;
import java.io.PrintStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class NgClosureRunner extends CommandLineRunner {

  private boolean minerrPass;
  private String minerrErrors, minerrUrl, minerrSeparator;
  private String minerrJsResourcePath;
  private Compiler cachedCompiler;

  protected NgClosureRunner(String[] args,
                            boolean minerrPass,
                            String minerrErrors,
                            String minerrUrl,
                            String minerrSeparator,
                            String minerrJsResourcePath) {
    super(args);
    this.minerrPass = minerrPass;
    this.minerrSeparator = minerrSeparator;
    this.minerrJsResourcePath = minerrJsResourcePath;

    if (minerrErrors != null) {
      this.minerrErrors = minerrErrors;
    } else {
      this.minerrErrors = "errors.json";
    }
    this.minerrUrl = minerrUrl;
  }

  private String loadTextResource(String asset) throws IOException {
    InputStream stream = getClass().getClassLoader().getResourceAsStream(asset);
    int size = stream.available();
    Scanner input = new Scanner(stream);
    StringBuilder buf = new StringBuilder(size);
    String lineSeparator = System.getProperty("line.separator");

    try {
      while(input.hasNextLine()) {
        buf.append(input.nextLine() + lineSeparator);
      }
      return buf.toString();
    } finally {
      input.close();
    }
  }

  private CompilerPass createMinerrPass() throws IOException {
    AbstractCompiler compiler = createCompiler();
    PrintStream output = new PrintStream(minerrErrors);
    String code = loadTextResource(minerrJsResourcePath);

    if (minerrUrl != null) {
      return new MinerrPass(
          compiler, output,
          MinerrPass.substituteInCode(code, minerrUrl, minerrSeparator));
    }
    return new MinerrPass(compiler, output);
  }

  @Override
  protected CompilerOptions createOptions() {
    CompilerOptions options = super.createOptions();

    if (minerrPass) {
      if (options.customPasses == null) {
        options.customPasses = ArrayListMultimap.create();
      }
      try {
        options.customPasses.put(CustomPassExecutionTime.BEFORE_OPTIMIZATIONS,
          createMinerrPass());
      } catch (IOException e) {
        System.err.println(e);
        System.exit(1);
      }
    }
    return options;
  }

  @Override
  protected Compiler createCompiler() {
    // Always use the same compiler.
    if (cachedCompiler != null) {
      return cachedCompiler;
    }
    cachedCompiler = super.createCompiler();
    return cachedCompiler;
  }

  public static void main(String[] args) {
    boolean minerrPass = false;
    String minerrErrors = "errors.json";
    String minerrUrl = null;
    String minerrSeparator = "/";
    String minerrJsResourcePath = "minErr.js";
    List<String> passthruArgs = new ArrayList<String>();

    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (arg.equals("--minerr_pass")) {
        minerrPass = true;
      } else if (arg.equals("--minerr_errors")) {
        minerrErrors = args[++i];
      } else if (arg.equals("--minerr_url")) {
        minerrUrl = args[++i];
      } else if (arg.equals("--minerr_separate_with_colon")) {
        minerrSeparator = ":";
      } else if (arg.equals("--minerr_js_resource_path")) {
        minerrJsResourcePath = args[++i];
      } else {
        passthruArgs.add(arg);
      }
    }

    NgClosureRunner runner = new NgClosureRunner(
        passthruArgs.toArray(new String[]{}),
        minerrPass, minerrErrors, minerrUrl, minerrSeparator,
        minerrJsResourcePath);

    if (runner.shouldRunCompiler()) {
      runner.run();
    } else {
      System.exit(-1);
    }
  }
}

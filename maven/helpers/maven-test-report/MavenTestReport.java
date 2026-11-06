// MavenTestReport reads one shared Maven run's Surefire/Failsafe XML reports
// and log once and writes a bounded result (empty for a pass) for each
// selected test class check, plus the reactor's own verify verdict.
//
// Usage:
//   java MavenTestReport --reports DIR --poms DIR --root DIR --log FILE --stderr FILE
//     --selection FILE --exit-code N --mode whole|narrowed --output DIR
//
// The selection file has one line per selected check:
//   <result path>\t<workspace-relative module directory>\t<class name>
// The caller computes result paths, so this program never sorts identifiers
// and identifiers never become file paths.
//
// Blame goes to the finest unit the run tells apart:
//   1. A failed test case fails its class's check.
//   2. A module whose goal failed (compilation, dependency resolution, plugin,
//      forked VM) fails the checks of that module. With --fail-at-end, other
//      modules still build; the ones Maven skipped did not run.
//   3. A failed test case without a selected check (an integration test or an
//      undiscovered class) fails the checks of its module.
//   4. Anything else (failures in modules without selected checks, failures
//      Maven does not attribute to a module) belongs to the reactor. A whole
//      run reports it through verify only; a narrowed run, which has no verify
//      check, fails every selected check.
//   5. A selected class with no test case did not run, and fails.
// The "all" result is the complete verdict, for plain verify helpers.

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class MavenTestReport {
  static final int DIAGNOSTIC_LIMIT = 32 << 10;
  static final int RESULT_LIMIT = 3 * DIAGNOSTIC_LIMIT;
  static final String[] REPORT_DIRS = {"target/surefire-reports/", "target/failsafe-reports/"};
  // Surefire and Failsafe log this when maven.test.failure.ignore hides a
  // failure from the exit status. It catches reports written elsewhere.
  static final String IGNORED_FAILURES = "There are test failures";

  /** Keeps the last DIAGNOSTIC_LIMIT characters, marking truncation. */
  static final class Tail {
    private final StringBuilder data = new StringBuilder();
    private boolean truncated;

    void add(String s) {
      if (s == null || s.isEmpty()) {
        return;
      }
      data.append(s);
      int extra = data.length() - DIAGNOSTIC_LIMIT;
      if (extra > 0) {
        data.delete(0, extra);
        truncated = true;
      }
    }

    boolean isEmpty() {
      return data.length() == 0;
    }

    @Override
    public String toString() {
      return truncated ? "[earlier output omitted; showing diagnostic tail]\n" + data : data.toString();
    }
  }

  static final class ClassResult {
    boolean observed;
    boolean failed;
    final Tail log = new Tail();
  }

  static String moduleOf(String relative) {
    for (String dir : REPORT_DIRS) {
      if (relative.startsWith(dir)) {
        return ".";
      }
      int at = relative.lastIndexOf("/" + dir);
      if (at >= 0) {
        return relative.substring(0, at);
      }
    }
    return null;
  }

  static String key(String module, String className) {
    return module + "\t" + className;
  }

  /** Streams one report, recording every test case by module and outer class. */
  static void readReport(Path file, String module, Map<String, ClassResult> classes, List<String> unattributed)
      throws Exception {
    XMLInputFactory factory = XMLInputFactory.newInstance();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    try (InputStream in = Files.newInputStream(file)) {
      XMLStreamReader xml = factory.createXMLStreamReader(in);
      ClassResult current = null;
      String testName = null;
      String testClass = null;
      String problem = null;
      StringBuilder text = null;
      boolean caseFailed = false;
      StringBuilder caseLog = null;
      while (xml.hasNext()) {
        int event = xml.next();
        if (event == XMLStreamConstants.START_ELEMENT) {
          String name = xml.getLocalName();
          if (name.equals("testcase")) {
            testClass = xml.getAttributeValue(null, "classname");
            testName = xml.getAttributeValue(null, "name");
            String outer = testClass == null ? "" : testClass;
            int nested = outer.indexOf('$');
            if (nested >= 0) {
              outer = outer.substring(0, nested);
            }
            current = classes.computeIfAbsent(key(module, outer), k -> new ClassResult());
            current.observed = true;
            caseFailed = false;
            caseLog = new StringBuilder();
          } else if (current != null && (name.equals("failure") || name.equals("error"))) {
            caseFailed = true;
            problem = name;
            String type = xml.getAttributeValue(null, "type");
            String message = xml.getAttributeValue(null, "message");
            caseLog.append(testClass).append('.').append(testName).append(' ').append(name);
            if (type != null) {
              caseLog.append(": ").append(type);
            }
            if (message != null) {
              caseLog.append(": ").append(message);
            }
            caseLog.append('\n');
            text = new StringBuilder();
          } else if (current != null && caseFailed && (name.equals("system-out") || name.equals("system-err"))) {
            problem = name;
            text = new StringBuilder();
          }
        } else if (text != null && (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)) {
          if (text.length() < DIAGNOSTIC_LIMIT) {
            text.append(xml.getText(), 0, Math.min(xml.getTextLength(), DIAGNOSTIC_LIMIT - text.length()));
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          String name = xml.getLocalName();
          if (text != null && name.equals(problem)) {
            String body = text.toString().strip();
            if (!body.isEmpty()) {
              if (name.startsWith("system-")) {
                caseLog.append("[").append(name).append("]\n");
              }
              caseLog.append(body).append('\n');
            }
            text = null;
            problem = null;
          } else if (name.equals("testcase") && current != null) {
            if (caseFailed) {
              current.failed = true;
              current.log.add(caseLog.toString());
            }
            current = null;
          }
        }
      }
      xml.close();
    }
  }

  static String tailOf(Path file) throws IOException {
    if (!Files.exists(file)) {
      return "";
    }
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
      long length = raf.length();
      long start = Math.max(0, length - DIAGNOSTIC_LIMIT);
      byte[] bytes = new byte[(int) (length - start)];
      raf.seek(start);
      raf.readFully(bytes);
      String text = new String(bytes, StandardCharsets.UTF_8);
      return start > 0 ? "[earlier output omitted; showing diagnostic tail]\n" + text : text;
    }
  }

  static boolean within(String dir, String root) {
    return root.equals(".") || dir.equals(root) || dir.startsWith(root + "/");
  }

  /**
   * Maps each POM's own artifactId to its directory. Artifact ids are unique
   * within a reactor, not a workspace, so a POM inside the reactor root wins;
   * ids that stay ambiguous are left out.
   */
  static Map<String, String> artifactDirectories(Path poms, String root) throws IOException {
    Map<String, List<String>> candidates = new HashMap<>();
    Map<String, String> dirs = new HashMap<>();
    if (!Files.isDirectory(poms)) {
      return dirs;
    }
    List<Path> files = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(poms)) {
      walk.filter(p -> p.getFileName().toString().equals("pom.xml")).sorted().forEach(files::add);
    }
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setExpandEntityReferences(false);
    try {
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    } catch (Exception e) {
      throw new IOException(e);
    }
    for (Path file : files) {
      String id = null;
      try {
        Element project = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
        for (Node child = project.getFirstChild(); child != null; child = child.getNextSibling()) {
          if (child instanceof Element && "artifactId".equals(child.getNodeName())) {
            id = child.getTextContent().strip();
          }
        }
      } catch (Exception e) {
        continue; // An unreadable POM cannot be blamed precisely; its failure is the reactor's.
      }
      if (id == null || id.isEmpty() || id.contains("${")) {
        continue;
      }
      String rel = poms.relativize(file.getParent()).toString().replace('\\', '/');
      String dir = rel.isEmpty() ? "." : rel;
      candidates.computeIfAbsent(id, k -> new ArrayList<>()).add(dir);
    }
    for (Map.Entry<String, List<String>> entry : candidates.entrySet()) {
      List<String> inside = new ArrayList<>();
      for (String dir : entry.getValue()) {
        if (within(dir, root)) {
          inside.add(dir);
        }
      }
      List<String> chosen = inside.isEmpty() ? entry.getValue() : inside;
      if (chosen.size() == 1) {
        dirs.put(entry.getKey(), chosen.get(0));
      }
    }
    return dirs;
  }

  static final class LogFacts {
    boolean ignoredFailures;
    // artifactId -> the failing goal lines, in log order.
    final Map<String, List<String>> failedProjects = new LinkedHashMap<>();
  }

  // Maven's own per-project verdict: "Failed to execute goal [plugin (exec)] on
  // project <artifactId>: <message>", one per failed project with --fail-at-end.
  static LogFacts readLog(Path file) throws IOException {
    LogFacts facts = new LogFacts();
    if (!Files.exists(file)) {
      return facts;
    }
    // Streamed line by line, so memory stays bounded however long the log is.
    // InputStreamReader replaces malformed bytes rather than failing.
    try (BufferedReader reader = new BufferedReader(
        new java.io.InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        readLogLine(facts, line);
      }
    }
    return facts;
  }

  static void readLogLine(LogFacts facts, String line) {
    if (line.contains(IGNORED_FAILURES)) {
      facts.ignoredFailures = true;
    }
    int goal = line.indexOf("Failed to execute goal ");
    if (goal < 0) {
      return;
    }
    int project = line.indexOf("on project ", goal);
    if (project < 0) {
      return;
    }
    String rest = line.substring(project + "on project ".length());
    int colon = rest.indexOf(':');
    String id = (colon < 0 ? rest : rest.substring(0, colon)).strip();
    List<String> lines = facts.failedProjects.computeIfAbsent(id, k -> new ArrayList<>());
    String trimmed = line.strip();
    if (!lines.contains(trimmed) && lines.size() < 8) {
      lines.add(trimmed);
    }
  }

  static String bounded(String s) {
    return s.length() > RESULT_LIMIT ? s.substring(0, RESULT_LIMIT) + "\n[remaining diagnostics omitted]\n" : s;
  }

  public static void main(String[] argv) throws Exception {
    Map<String, String> args = new LinkedHashMap<>();
    for (int i = 0; i + 1 < argv.length; i += 2) {
      if (!argv[i].startsWith("--")) {
        throw new IllegalArgumentException("unexpected argument " + argv[i]);
      }
      args.put(argv[i].substring(2), argv[i + 1]);
    }
    Path reports = Paths.get(args.get("reports"));
    Path output = Paths.get(args.get("output"));
    int exitCode = Integer.parseInt(args.get("exit-code"));
    Path log = Paths.get(args.get("log"));
    Path stderr = Paths.get(args.get("stderr"));

    // Selected checks, in the caller's order.
    List<String[]> selection = new ArrayList<>();
    Map<String, Boolean> selectedKeys = new LinkedHashMap<>();
    for (String line : Files.readAllLines(Paths.get(args.get("selection")), StandardCharsets.UTF_8)) {
      if (line.isEmpty()) {
        continue;
      }
      String[] parts = line.split("\t", -1);
      if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
        throw new IllegalArgumentException("invalid selection line: " + line);
      }
      if (selectedKeys.put(key(parts[1], parts[2]), true) != null) {
        throw new IllegalArgumentException("duplicate selected class " + parts[1] + "/" + parts[2]);
      }
      selection.add(parts);
    }

    String mode = args.getOrDefault("mode", "narrowed");
    boolean whole = mode.equals("whole");
    Set<String> selectedModules = new HashSet<>();
    for (String[] parts : selection) {
      selectedModules.add(parts[1]);
    }

    Map<String, ClassResult> classes = new TreeMap<>();
    List<String> unattributed = new ArrayList<>();
    List<Path> files = new ArrayList<>();
    if (Files.isDirectory(reports)) {
      try (Stream<Path> walk = Files.walk(reports)) {
        walk.filter(p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml"))
            .sorted()
            .forEach(files::add);
      }
    }
    for (Path file : files) {
      String relative = reports.relativize(file).toString().replace('\\', '/');
      String module = moduleOf(relative);
      if (module == null) {
        continue;
      }
      try {
        readReport(file, module, classes, unattributed);
      } catch (Exception e) {
        unattributed.add("unreadable test report " + relative + ": " + e.getMessage() + "\n");
      }
    }

    String logTail = tailOf(log);
    String stderrTail = tailOf(stderr);
    LogFacts facts = readLog(log);
    Map<String, String> artifactDirs = artifactDirectories(Paths.get(args.get("poms")), args.get("root"));

    // Rule 2: failed modules, by directory. Unmapped projects are the reactor's.
    Map<String, Tail> moduleFailures = new TreeMap<>();
    TreeSet<String> reactorProjects = new TreeSet<>();
    Tail reactorProjectLog = new Tail();
    for (Map.Entry<String, List<String>> entry : facts.failedProjects.entrySet()) {
      String dir = artifactDirs.get(entry.getKey());
      String lines = String.join("\n", entry.getValue()) + "\n";
      if (dir == null) {
        reactorProjects.add(entry.getKey());
        reactorProjectLog.add(lines);
      } else {
        moduleFailures.computeIfAbsent(dir, k -> new Tail()).add(lines);
      }
    }

    // Rule 3: failed classes without a selected check, by module.
    boolean anyFailure = false;
    Map<String, TreeSet<String>> uncheckedByModule = new TreeMap<>();
    Map<String, Tail> uncheckedLogByModule = new TreeMap<>();
    for (Map.Entry<String, ClassResult> entry : classes.entrySet()) {
      ClassResult result = entry.getValue();
      if (!result.failed) {
        continue;
      }
      anyFailure = true;
      if (!selectedKeys.containsKey(entry.getKey())) {
        String[] parts = entry.getKey().split("\t", -1);
        uncheckedByModule.computeIfAbsent(parts[0], k -> new TreeSet<>()).add(parts[0] + "/" + parts[1]);
        uncheckedLogByModule.computeIfAbsent(parts[0], k -> new Tail()).add(result.log.toString());
      }
    }

    // Rule 4: what no selected module owns.
    StringBuilder reactor = new StringBuilder();
    TreeSet<String> foreignUnchecked = new TreeSet<>();
    Tail foreignUncheckedLog = new Tail();
    for (Map.Entry<String, TreeSet<String>> entry : uncheckedByModule.entrySet()) {
      if (!selectedModules.contains(entry.getKey())) {
        foreignUnchecked.addAll(entry.getValue());
        foreignUncheckedLog.add(uncheckedLogByModule.get(entry.getKey()).toString());
      }
    }
    if (!foreignUnchecked.isEmpty() || !unattributed.isEmpty()) {
      reactor.append("failures in test classes without selected test checks: ")
          .append(foreignUnchecked.isEmpty() ? "(unattributed)" : String.join(", ", foreignUnchecked))
          .append('\n').append(foreignUncheckedLog);
      for (String u : unattributed) {
        reactor.append(u);
      }
    }
    TreeSet<String> foreignModules = new TreeSet<>();
    Tail foreignModuleLog = new Tail();
    for (Map.Entry<String, Tail> entry : moduleFailures.entrySet()) {
      if (!selectedModules.contains(entry.getKey())) {
        foreignModules.add(entry.getKey());
        foreignModuleLog.add(entry.getValue().toString());
      }
    }
    if (!foreignModules.isEmpty()) {
      reactor.append("Maven failed in modules without selected test checks: ")
          .append(String.join(", ", foreignModules)).append('\n').append(foreignModuleLog);
    }
    if (!reactorProjects.isEmpty()) {
      reactor.append("Maven failed in projects outside any module directory: ")
          .append(String.join(", ", reactorProjects)).append('\n').append(reactorProjectLog);
    }
    boolean explained = !facts.failedProjects.isEmpty();
    if (exitCode != 0 && !explained) {
      reactor.append("shared Maven command failed (exit ").append(exitCode).append(")\n");
    } else if (!anyFailure && unattributed.isEmpty() && facts.ignoredFailures) {
      // Failures were logged but no standard report holds them, e.g. a custom
      // reportsDirectory. They must not pass silently.
      reactor.append("Maven reported test failures outside the standard Surefire/Failsafe XML reports\n");
    }
    if (reactor.length() > 0) {
      reactor.append(logTail).append(stderrTail);
    }
    String reactorFailure = reactor.toString();

    Files.createDirectories(output);
    for (String[] parts : selection) {
      String module = parts[1];
      ClassResult result = classes.get(key(module, parts[2]));
      String failure;
      if (result != null && result.failed) {
        failure = parts[2] + " failed\n" + result.log;
      } else if (moduleFailures.containsKey(module)) {
        failure = "Maven failed in module " + module + "\n" + moduleFailures.get(module) + logTail + stderrTail;
      } else if (uncheckedByModule.containsKey(module)) {
        failure = "failures in test classes without selected test checks: "
            + String.join(", ", uncheckedByModule.get(module)) + "\n" + uncheckedLogByModule.get(module);
      } else if (!whole && !reactorFailure.isEmpty()) {
        failure = reactorFailure;
      } else if (result == null) {
        failure = parts[2] + " did not run: Maven completed without a test case for it in module " + module
            + (moduleFailures.isEmpty() && reactorFailure.isEmpty()
                ? "; check active profiles, source layout, skip flags, and Surefire XML report configuration\n"
                : "; Maven stopped building it after failures in: "
                    + String.join(", ", moduleFailures.keySet().isEmpty() ? reactorProjects : moduleFailures.keySet())
                    + "\n" + logTail);
      } else {
        failure = "";
      }
      Path file = output.resolve(parts[0]).normalize();
      if (!file.startsWith(output)) {
        throw new IllegalArgumentException("result path escapes output: " + parts[0]);
      }
      Files.createDirectories(file.getParent());
      Files.writeString(file, bounded(failure), StandardCharsets.UTF_8);
    }

    // verify owns only what no class check owns.
    Files.writeString(output.resolve("verify"), bounded(reactorFailure), StandardCharsets.UTF_8);

    // The complete verdict, for the plain collection helper.
    StringBuilder all = new StringBuilder();
    TreeSet<String> failedClasses = new TreeSet<>();
    Tail failedLog = new Tail();
    for (Map.Entry<String, ClassResult> entry : classes.entrySet()) {
      if (entry.getValue().failed) {
        String[] parts = entry.getKey().split("\t", -1);
        failedClasses.add(parts[0] + "/" + parts[1]);
        failedLog.add(entry.getValue().log.toString());
      }
    }
    if (!failedClasses.isEmpty()) {
      all.append("failed test classes: ").append(String.join(", ", failedClasses)).append('\n').append(failedLog);
    }
    if (!moduleFailures.isEmpty() || !reactorProjects.isEmpty() || exitCode != 0) {
      all.append("Maven failed (exit ").append(exitCode).append(")\n").append(logTail).append(stderrTail);
    } else if (facts.ignoredFailures && failedClasses.isEmpty()) {
      all.append("Maven reported test failures outside the standard Surefire/Failsafe XML reports\n").append(logTail);
    } else if (!unattributed.isEmpty()) {
      all.append(String.join("", unattributed));
    }
    Files.writeString(output.resolve("all"), bounded(all.toString()), StandardCharsets.UTF_8);
  }
}

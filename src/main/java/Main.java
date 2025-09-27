import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class Main {
    public static void main(String[] args) throws Exception {
        System.out.print("$ ");
        String saved = saveTtyState();
        BufferedReader nonInteractiveReader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            String input;
            if (System.console() != null) {
                setTerminalRawMode();
                try {
                    input = takeInput(false); // <-- do NOT echo, and do NOT print newline
                } finally {
                    restoreTtyState(saved);
                }
            } else {
                BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
                input = br.readLine(); // read whole line

                // Echo the typed command line ourselves, replacing the existing "$ " prompt
                // line
                if (input == null)
                    break; // EOF
                System.out.print("\r\033[2K$ " + input + "\n");
                System.out.flush();
            }

            if (input == null)
                break; // EOF
            System.out.print("\r\033[2K$ " + input + "\n");
            System.out.flush();

            if (input.isEmpty()) { // blank line: print next prompt
                System.out.print("$ ");
                continue;
            }

            // detect redirection (> / 2> / >>) safely in ONE pass
            boolean flag = false, error = false, append = false;
            for (int i = 0; i < input.length(); i++) {
                if (input.charAt(i) == '>') {
                    // 2> : guard i>0
                    if (i > 0 && input.charAt(i - 1) == '2') {
                        error = true;
                        input = input.substring(0, i - 1) + input.substring(i);
                        i--; // adjust since we removed one char
                    }
                    // >> (append)
                    if (i + 1 < input.length() && input.charAt(i + 1) == '>') {
                        append = true;
                        input = input.substring(0, i) + input.substring(i + 1);
                    }
                    flag = true;
                    break;
                }
            }

            if (flag) {
                redirect(input, error, append);
                System.out.print("$ ");
                continue;
            }

            // ---- Builtins and exec ----
            String[] headTokens = input.split(" ");
            if (headTokens.length == 0) {
                System.out.print("$ ");
                continue;
            }

            if (headTokens[0].equals("echo")) {
                if (input.length() >= 5) {
                    System.out.println(print(input.substring(5)));
                } else {
                    System.out.println();
                }
                System.out.print("$ ");
                continue;
            }

            if (headTokens[0].equals("cat")) {
                String[] files = convert(input); // extracts quoted/paths
                if (files.length > 0) {
                    for (String f : files)
                        content(f);
                }
                System.out.print("$ ");
                continue;
            }

            if (input.charAt(0) == '\'') {
                int ind = 1;
                for (int i = 1; i < input.length(); i++) {
                    if (input.charAt(i) == '\'') {
                        ind = i;
                        break;
                    }
                }
                if (ind + 2 <= input.length())
                    content(input.substring(ind + 2));
                System.out.print("$ ");
                continue;
            }

            if (input.charAt(0) == '"') {
                int ind = 1;
                for (int i = 1; i < input.length(); i++) {
                    if (input.charAt(i) == '"') {
                        ind = i;
                        break;
                    }
                }
                if (ind + 2 <= input.length())
                    content(input.substring(ind + 2));
                System.out.print("$ ");
                continue;
            }

            if (headTokens[0].equals("cd")) {
                if (headTokens.length < 2) {
                    System.out.print("$ ");
                    continue;
                }
                String path = headTokens[1];
                if (!path.isEmpty() && path.charAt(0) == '.') {
                    change(path);
                    System.out.print("$ ");
                    continue;
                }
                if (!path.isEmpty() && path.charAt(0) == '~') {
                    System.setProperty("user.dir", System.getenv("HOME"));
                    System.out.print("$ ");
                    continue;
                }
                String p2 = exists(path);
                if (p2 != null) {
                    System.setProperty("user.dir", p2);
                } else {
                    System.out.println("cd: " + headTokens[1] + ": No such file or directory");
                }
                System.out.print("$ ");
                continue;
            }

            if (input.equals("pwd")) {
                System.out.println(System.getProperty("user.dir"));
                System.out.print("$ ");
                continue;
            }

            if (headTokens[0].equals("pwd")) {
                System.out.println(Path.of("").toAbsolutePath());
                System.out.print("$ ");
                continue;
            }

            if (check(input)) {
                Process process = Runtime.getRuntime().exec(input.split(" "));
                process.getInputStream().transferTo(System.out);
                System.out.print("$ ");
                continue;
            }

            String str1 = (input.length() >= 4) ? input.substring(0, 4) : input;
            if (str1.equals("type")) {
                String str = (input.length() >= 5) ? input.substring(5) : "";
                if (str.equals("echo") || str.equals("exit") || str.equals("pwd") || str.equals("type")) {
                    System.out.println(str + " is a shell builtin");
                } else {
                    System.out.println(find(str));
                }
            } else if (str1.equals("echo")) {
                System.out.println((input.length() >= 5) ? input.substring(5) : "");
            } else if (str1.equals("exit")) {
                System.exit(0);
            } else {
                System.out.println(input + ": not found");
            }
            System.out.print("$ ");
        }
    }

    // Redraw prompt + current buffer
    static void redraw(StringBuilder buf) {
        System.out.print("\r\033[2K$ ");
        System.out.print(buf);
        System.out.flush();
    }

    // Return completion for first word, or null if no match
    static String completeFirstWord(String s) {
        String[] builtins = { "echo", "exit" };
        for (String b : builtins) {
            if (b.startsWith(s))
                return b + " "; // include trailing space
        }
        return null;
    }

    // Convenience overload (kept if you call takeInput() elsewhere)
    static String takeInput() throws IOException {
        boolean echoKeys = (System.console() != null);
        return takeInput(echoKeys);
    }

    static String takeInput(boolean echoKeys) throws IOException {
        final InputStream in = System.in;
        StringBuilder sb = new StringBuilder();

        while (true) {
            int r = in.read();
            if (r == -1)
                return null; // EOF
            char c = (char) r;

            // Enter
            if (c == '\n' || c == '\r') {
                if (echoKeys) {
                    System.out.print("\r\n");
                    System.out.flush();
                }
                return sb.toString();
            }

            // Tab completion (complete first word only)
            if (c == '\t') {
                boolean hasSpace = false;
                for (int i = 0; i < sb.length(); i++) {
                    if (Character.isWhitespace(sb.charAt(i))) {
                        hasSpace = true;
                        break;
                    }
                }
                if (!hasSpace) {
                    String comp = completeFirstWord(sb.toString()); // e.g., returns "echo "
                    if (comp != null) {
                        sb.setLength(0);
                        sb.append(comp);
                        redraw(sb); // show `$ echo ` even if echoKeys == false
                    } else {
                        System.out.print("\007"); // bell
                        System.out.flush();
                    }
                } else {
                    System.out.print("\007");
                    System.out.flush();
                }
                continue;
            }

            // Backspace
            if (c == 127 || c == 8) {
                if (sb.length() > 0) {
                    sb.deleteCharAt(sb.length() - 1);
                    if (echoKeys) {
                        System.out.print("\b \b");
                        System.out.flush();
                    }
                }
                continue;
            }

            // Printable ASCII
            else {
                sb.append(c);
                if (echoKeys) {
                    System.out.print(c);
                    System.out.flush();
                }
                continue;
            }

            // ignore other controls
        }
    }

    static String saveTtyState() {
        try {
            Process p = new ProcessBuilder("/bin/sh", "-c", "stty -g </dev/tty")
                    .redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor();
            return new String(out).trim();
        } catch (Exception e) {
            return null;
        }
    }

    static void setTerminalRawMode() {
        try {
            new ProcessBuilder("/bin/sh", "-c",
                    "stty -echo -icanon min 1 time 0 </dev/tty")
                    .inheritIO().start().waitFor();
        } catch (Exception ignored) {
        }
    }

    static void restoreTtyState(String state) {
        if (state == null || state.isEmpty())
            return;
        try {
            new ProcessBuilder("/bin/sh", "-c",
                    "stty " + state + " </dev/tty")
                    .inheritIO().start().waitFor();
        } catch (Exception ignored) {
        }
    }

    // ---- Redirection ----
    static void redirect(String input, boolean error, boolean append) throws IOException, InterruptedException {
        String[] inputs = input.split(" ");
        if (inputs.length == 0)
            return;

        if (inputs[0].equals("echo")) {
            Deque<String> dq = new ArrayDeque<>();
            String dest = "";
            for (int i = 0; i < input.length();) {
                if (input.charAt(i) == '\'') {
                    int j = i + 1;
                    StringBuilder sb = new StringBuilder();
                    while (j < input.length() && input.charAt(j) != '\'') {
                        sb.append(input.charAt(j));
                        j++;
                    }
                    i = Math.min(j + 1, input.length());
                    dq.offerLast(sb.toString());
                } else if (input.charAt(i) == '\"') {
                    int j = i + 1;
                    StringBuilder sb = new StringBuilder();
                    while (j < input.length() && input.charAt(j) != '\"') {
                        sb.append(input.charAt(j));
                        j++;
                    }
                    i = Math.min(j + 1, input.length());
                    dq.offerLast(sb.toString());
                } else if (i + 1 < input.length() && input.charAt(i) == '1' && input.charAt(i + 1) == '>') {
                    dest = input.substring(i + 3);
                    break;
                } else if (input.charAt(i) == '>') {
                    dest = input.substring(i + 2);
                    break;
                } else {
                    i++;
                }
            }
            dest = dest.trim();
            if ((dest.startsWith("\"") && dest.endsWith("\"")) || (dest.startsWith("'") && dest.endsWith("'"))) {
                dest = dest.substring(1, dest.length() - 1);
            }

            StringBuilder outBuf = new StringBuilder();
            for (Iterator<String> it = dq.iterator(); it.hasNext();) {
                outBuf.append(it.next());
                if (it.hasNext())
                    outBuf.append(' ');
            }

            Path outPath = Path.of(dest);
            Path parent = outPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            if (error) {
                // echo has no stderr; but if asked, write nothing to stdout and
                // just create/append empty (closest to expected behavior for tests)
                if (append) {
                    Files.write(outPath, new byte[0],
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } else {
                    Files.write(outPath, new byte[0],
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
            } else {
                byte[] bytes = (outBuf.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                if (append) {
                    Files.write(outPath, bytes,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } else {
                    Files.write(outPath, bytes,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
            }

        } else if (inputs[0].equals("ls")) {
            int gt = input.lastIndexOf('>');
            if (gt != -1) {
                String lhs = input.substring(0, gt).trim();
                String right = input.substring(gt + 1).trim();
                String dest = unquote(firstToken(right));

                Path out = Path.of(dest);
                Path parent = out.getParent();
                if (parent != null && !Files.exists(parent))
                    Files.createDirectories(parent);

                String[] argv = lhs.split("\\s+");
                String exe = resolveOnPath(argv[0]);
                if (exe == null) {
                    System.out.println(argv[0] + ": not found");
                } else {
                    argv[0] = exe;
                    ProcessBuilder pb = new ProcessBuilder(argv);
                    pb.directory(new File(System.getProperty("user.dir")));

                    if (error) {
                        if (append)
                            pb.redirectError(ProcessBuilder.Redirect.appendTo(out.toFile()));
                        else
                            pb.redirectError(ProcessBuilder.Redirect.to(out.toFile()));
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    } else {
                        if (append)
                            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out.toFile()));
                        else
                            pb.redirectOutput(out.toFile());
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                    Process p = pb.start();
                    p.waitFor();
                }
            } else {
                ProcessBuilder pb = new ProcessBuilder(input.trim().split("\\s+"));
                pb.directory(new File(System.getProperty("user.dir")));
                pb.inheritIO();
                Process p = pb.start();
                p.waitFor();
            }

        } else if (inputs[0].equals("cat")) {
            int gt = input.lastIndexOf('>');
            if (gt == -1)
                return;

            int j = gt - 1;
            while (j >= 0 && Character.isWhitespace(input.charAt(j)))
                j--;
            boolean oneRedir = (j >= 0 && input.charAt(j) == '1' && !(j - 1 >= 0 && input.charAt(j - 1) == '-'));

            String lhs = input.substring(0, oneRedir ? j : gt).trim();
            String rhs = input.substring(gt + 1).trim();

            String dest = unquote(firstToken(rhs));
            Path out = Path.of(dest);
            Path parent = out.getParent();
            if (parent != null && !Files.exists(parent))
                Files.createDirectories(parent);

            String srcPart = lhs.startsWith("cat") ? lhs.substring(3).trim() : lhs;
            List<String> sources = tokenizeArgs(srcPart);

            if (sources.isEmpty()) {
                Files.write(out, new byte[0],
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return;
            }

            List<String> argv = new ArrayList<>();
            String catExe = resolveOnPath("cat");
            argv.add(catExe != null ? catExe : "cat");
            argv.addAll(sources);

            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(new File(System.getProperty("user.dir")));
            if (error) {
                if (append)
                    pb.redirectError(ProcessBuilder.Redirect.appendTo(out.toFile()));
                else
                    pb.redirectError(ProcessBuilder.Redirect.to(out.toFile()));
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            } else {
                if (append)
                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out.toFile()));
                else
                    pb.redirectOutput(out.toFile());
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            Process p = pb.start();
            p.waitFor();
        }
    }

    static String firstToken(String s) {
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i)))
            i++;
        return s.substring(0, i);
    }

    static String unquote(String s) {
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    static String resolveOnPath(String name) {
        if (name.contains(File.separator))
            return new File(name).getPath();
        String path = System.getenv("PATH");
        if (path == null)
            return null;
        for (String dir : path.split(File.pathSeparator)) {
            File f = new File(dir, name);
            if (f.isFile() && f.canExecute())
                return f.getAbsolutePath();
        }
        return null;
    }

    static String[] convert(String input) {
        Deque<String> response = new ArrayDeque<>();
        int ind = 0;
        while (ind < input.length()) {
            if (input.charAt(ind) == '/') {
                StringBuilder sb = new StringBuilder();
                while (ind < input.length() && input.charAt(ind) != ' ') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                response.offerLast(sb.toString());
            } else if (input.charAt(ind) == '\'') {
                ind++;
                StringBuilder sb = new StringBuilder();
                while (ind < input.length() && input.charAt(ind) != '\'') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                response.offerLast(sb.toString());
            } else if (input.charAt(ind) == '\"') {
                ind++;
                StringBuilder sb = new StringBuilder();
                while (ind < input.length() && input.charAt(ind) != '\"') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                response.offerLast(sb.toString());
            }
            ind++;
        }
        return response.toArray(new String[0]);
    }

    static void content(String file) {
        try {
            Process p = new ProcessBuilder("cat", file)
                    .redirectErrorStream(true).start();
            p.getInputStream().transferTo(System.out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static String print(String input) {
        Deque<String> dq = new ArrayDeque<>();
        int ind = 0;
        while (ind < input.length()) {
            if (input.charAt(ind) == '"') {
                StringBuilder sb = new StringBuilder();
                ind++;
                while (ind != input.length() && input.charAt(ind) != '"') {
                    if (input.charAt(ind) == '\\' && ind + 1 < input.length()) {
                        sb.append(input.charAt(ind + 1));
                        ind += 2;
                        continue;
                    }
                    sb.append(input.charAt(ind));
                    ind++;
                }
                if (ind < input.length())
                    ind++;
                dq.offerLast(sb.toString());
            } else if (input.charAt(ind) == '\'') {
                StringBuilder sb = new StringBuilder();
                ind++;
                while (ind != input.length() && input.charAt(ind) != '\'') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                if (ind < input.length())
                    ind++;
                dq.offerLast(sb.toString());
            } else if (input.charAt(ind) == '\\') {
                if (ind + 1 < input.length())
                    dq.offerLast("" + input.charAt(ind + 1));
                ind += 2;
                continue;
            } else if (input.charAt(ind) == ' ') {
                if (dq.size() > 0 && " ".equals(dq.peekLast())) {
                    ind++;
                    continue;
                }
                dq.offerLast(" ");
                ind++;
            } else {
                StringBuilder sb = new StringBuilder();
                while (ind < input.length() && input.charAt(ind) != ' ' &&
                        input.charAt(ind) != '\'' && input.charAt(ind) != '"' &&
                        input.charAt(ind) != '\\') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                dq.offerLast(sb.toString());
            }
        }
        StringBuilder sb = new StringBuilder();
        while (!dq.isEmpty())
            sb.append(dq.pollFirst());
        return sb.toString();
    }

    static List<String> tokenizeArgs(String s) {
        ArrayList<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inS = false, inD = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                cur.append(c);
                esc = false;
                continue;
            }
            if (c == '\\') {
                esc = true;
                continue;
            }
            if (c == '\'' && !inD) {
                inS = !inS;
                continue;
            }
            if (c == '"' && !inS) {
                inD = !inD;
                continue;
            }
            if (Character.isWhitespace(c) && !inS && !inD) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0)
            out.add(cur.toString());
        return out;
    }

    static void change(String input) {
        String path = System.getProperty("user.dir");
        Deque<String> dq = new ArrayDeque<>();
        String[] paths = path.split("/");
        for (String p : paths)
            dq.offer(p);
        if (!dq.isEmpty())
            dq.pollFirst();
        int ind = 0;
        while (ind < input.length()) {
            if (ind + 2 < input.length() && input.substring(ind, ind + 3).equals("../")) {
                if (!dq.isEmpty())
                    dq.pollLast();
                ind += 3;
                continue;
            } else if (input.charAt(ind) == '/') {
                ind++;
                StringBuilder sb = new StringBuilder();
                while (ind < input.length() && input.charAt(ind) != '/') {
                    sb.append(input.charAt(ind));
                    ind++;
                }
                dq.offer(sb.toString());
            } else {
                ind++;
            }
        }
        StringBuilder sb = new StringBuilder();
        while (!dq.isEmpty()) {
            sb.append('/').append(dq.pollFirst());
        }
        System.setProperty("user.dir", sb.toString());
    }

    static String exists(String input) {
        Path path = Path.of(input);
        File file = path.toFile();
        if (file.exists() && file.isDirectory())
            return file.getAbsolutePath();
        return null;
    }

    static boolean check(String input) {
        String[] inputs = input.split(" ");
        String path = System.getenv("PATH");
        if (path == null)
            return false;
        String[] commands = path.split(":");
        for (String dir : commands) {
            File file = new File(dir, inputs[0]);
            if (file.exists() && file.canExecute())
                return true;
        }
        return false;
    }

    static String find(String str) {
        String path = System.getenv("PATH");
        if (path == null)
            return str + ": not found";
        String[] commands = path.split(":");
        for (String dir : commands) {
            File file = new File(dir, str);
            if (file.exists() && file.canExecute()) {
                return str + " is " + file.getAbsolutePath();
            }
        }
        return str + ": not found";
    }
}
